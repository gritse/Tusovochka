package smithereen.mcp;

import com.google.gson.JsonObject;

import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.model.Group;
import smithereen.model.MailMessage;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.OwnedContentObject;
import smithereen.model.Post;
import smithereen.model.PostLikeObject;
import smithereen.model.User;
import smithereen.model.apps.AppAccessToken;
import smithereen.model.apps.ClientAppPermission;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.comments.CommentableObjectType;
import smithereen.model.notifications.RealtimeNotification;
import smithereen.model.objects.Actor;
import smithereen.model.photos.Photo;
import smithereen.text.TextProcessor;
import smithereen.util.XTEA;
import spark.utils.StringUtils;

/**
 * Pushes the user's real-time notifications to AI agents connected to {@code /mcp/events}, the event stream behind the
 * Claude Code channel bridge in {@code channel/}. Every event the web notifier pops up is sent here too, as plain
 * JSON with the IDs the MCP tools take, so the agent can act on it without a lookup. The user's notifier settings
 * do not apply: they are about what a person wants to see pop up in the browser.
 */
public class AgentEvents {
	private static final Logger LOG = LoggerFactory.getLogger(AgentEvents.class);
	private static final AgentEvents instance = new AgentEvents();
	/** How much of someone's text goes into the summary of an event about one's own content, like a like. */
	private static final int PREVIEW_LENGTH = 80;

	private final HashMap<Integer, List<Connection>> connectionsByUserID = new HashMap<>();
	private final HashMap<Session, Connection> connectionsBySession = new HashMap<>();

	public static AgentEvents getInstance() {
		return instance;
	}

	public record Connection(int userID, AppAccessToken token, Session session) {
		void send(String message) {
			try {
				session.getRemote().sendString(message);
			} catch (IOException x) {
				LOG.debug("Failed to send an agent event to {}", session, x);
				session.close();
			}
		}
	}

	public synchronized void register(Connection conn) {
		connectionsByUserID.computeIfAbsent(conn.userID(), id -> new ArrayList<>()).add(conn);
		connectionsBySession.put(conn.session(), conn);
		LOG.debug("Agent event stream {} opened for user {}", conn.session(), conn.userID());
	}

	public synchronized void unregister(Session session) {
		Connection conn = connectionsBySession.remove(session);
		if (conn == null)
			return;
		List<Connection> connections = connectionsByUserID.get(conn.userID());
		connections.remove(conn);
		if (connections.isEmpty())
			connectionsByUserID.remove(conn.userID());
		LOG.debug("Agent event stream {} closed for user {}", session, conn.userID());
	}

	private synchronized List<Connection> connectionsFor(int userID) {
		List<Connection> connections = connectionsByUserID.get(userID);
		return connections == null ? List.of() : new ArrayList<>(connections);
	}

	/**
	 * Called with the same arguments as {@link smithereen.controllers.NotificationsController#sendRealtimeNotifications}.
	 */
	public void publish(ApplicationContext ctx, User user, String id, RealtimeNotification.Type type, Object object, OwnedContentObject relatedObject, Actor actor) {
		List<Connection> connections = connectionsFor(user.id);
		if (connections.isEmpty())
			return;
		String message;
		try {
			JsonObject envelope = new JsonObject();
			envelope.addProperty("type", "event");
			envelope.add("event", toJson(id, type, object, relatedObject, actor));
			message = envelope.toString();
		} catch (Exception x) {
			LOG.warn("Failed to build the agent event for notification {} of type {}", id, type, x);
			return;
		}
		for (Connection conn : connections) {
			if (type == RealtimeNotification.Type.MAIL_MESSAGE && !hasScope(conn.token(), ClientAppPermission.MESSAGES_READ))
				continue;
			Thread.ofVirtual().start(() -> {
				// Revoking a token in Settings, or a password change, must also end the streams opened with it
				AppAccessToken token = ctx.getAppsController().getAccessTokenOrNull(conn.token().id());
				if (token == null || (token.expiresAt() != null && token.expiresAt().isBefore(Instant.now()))) {
					conn.session().close(4001, "access token revoked or expired");
					return;
				}
				conn.send(message);
			});
		}
	}

	public static boolean hasScope(AppAccessToken token, ClientAppPermission scope) {
		return token.permissions().contains(scope) || token.permissions().contains(ClientAppPermission.PASSWORD_GRANT_USED);
	}

	/**
	 * One flat object per event. {@code summary} is an English sentence for a human or a model, {@code text} the full
	 * text of what someone wrote, in Markdown. The other fields are IDs in the conventions of the MCP tools: an
	 * {@code owner_id} or {@code from_id} is a user ID or a negative group ID, a wall comment is addressed as its
	 * top-level {@code post_id} plus its own {@code comment_id}, messages, photos and photo comments use string IDs.
	 */
	static JsonObject toJson(String id, RealtimeNotification.Type type, Object object, OwnedContentObject relatedObject, Actor actor) {
		JsonObject ev = new JsonObject();
		ev.addProperty("id", id);
		ev.addProperty("kind", type.name().toLowerCase(Locale.ROOT));
		String name = actor == null ? "" : actor.getName();
		if (actor != null) {
			ev.addProperty("from_id", actor instanceof Group ? -actor.getLocalID() : actor.getLocalID());
			ev.addProperty("from_name", name);
			if (StringUtils.isNotEmpty(actor.username))
				ev.addProperty("from_username", actor.username);
		}

		String text = null;
		String url = actor == null ? null : actor.getAbsoluteProfileURL();
		switch (object) {
			case Post post -> {
				ev.addProperty("owner_id", post.ownerID);
				if (post.getReplyLevel() == 0) {
					ev.addProperty("post_id", post.id);
					url = post.getInternalURL().toString();
				} else {
					int topLevelID = post.replyKey.getFirst();
					ev.addProperty("post_id", topLevelID);
					ev.addProperty("comment_id", post.id);
					if (post.getReplyLevel() > 1)
						ev.addProperty("reply_to_comment", post.replyKey.getLast());
					url = Config.localURI("/posts/" + topLevelID + "#comment" + post.id).toString();
				}
				text = markdown(post);
			}
			case Comment comment -> {
				ev.addProperty("comment_id", XTEA.encodeObjectID(comment.id, ObfuscatedObjectIDType.COMMENT));
				ev.addProperty(comment.parentObjectID.type() == CommentableObjectType.PHOTO ? "photo_id" : "topic_id", comment.parentObjectID.getIdString());
				if (relatedObject instanceof Photo photo)
					url = photo.getAbsoluteURL() + "#comment" + comment.getIDString();
				else if (relatedObject instanceof BoardTopic topic)
					url = Config.localURI(topic.getURL() + "#comment" + comment.getIDString()).toString();
				text = markdown(comment);
			}
			case Photo photo -> {
				ev.addProperty("photo_id", photo.getIdString());
				url = photo.getAbsoluteURL();
			}
			case MailMessage msg -> {
				ev.addProperty("message_id", msg.getIdString());
				if (StringUtils.isNotEmpty(msg.subject))
					ev.addProperty("subject", msg.subject);
				text = HtmlToMarkdown.convert(msg.text);
				url = Config.localURI("/my/mail/messages/" + msg.getIdString()).toString();
			}
			case Group group -> {
				ev.addProperty("group_id", group.id);
				ev.addProperty("group_name", group.name);
				url = group.getAbsoluteProfileURL();
			}
			case null, default -> {}
		}

		ev.addProperty("summary", summary(type, object, relatedObject, actor, name));
		// Likes and reposts are about the user's own content: its text is already known, the summary quotes the start
		if (StringUtils.isNotEmpty(text) && type != RealtimeNotification.Type.LIKE && type != RealtimeNotification.Type.REPOST)
			ev.addProperty("text", text);
		if (url != null)
			ev.addProperty("url", url);
		return ev;
	}

	private static String summary(RealtimeNotification.Type type, Object object, OwnedContentObject relatedObject, Actor actor, String name) {
		return switch (type) {
			case REPLY -> switch (object) {
				case Post post when post.getReplyLevel() == 1 -> name + " commented on your post" + quote(relatedObject);
				case Comment comment when comment.getReplyLevel() == 0 && comment.parentObjectID.type() == CommentableObjectType.PHOTO -> name + " commented on your photo";
				case null, default -> name + " replied to your comment";
			};
			case LIKE -> name + " liked your " + noun(object) + quote(object);
			case MENTION -> name + " mentioned you in a " + noun(object);
			case REPOST -> name + " reposted your " + noun(object) + quote(object);
			case WALL_POST -> name + " posted on your wall";
			case INVITE_SIGNUP -> name + " signed up with your invitation";
			case FOLLOW -> name + " followed you";
			case FRIEND_REQUEST -> name + " sent you a friend request";
			case FRIEND_REQUEST_ACCEPTED -> name + " accepted your friend request";
			case MAIL_MESSAGE -> name + " sent you a private message";
			case GROUP_INVITE -> name + " invited you to the group " + ((Group) object).name;
			case EVENT_INVITE -> name + " invited you to the event " + ((Group) object).name;
			case GROUP_REQUEST_ACCEPTED -> (actor instanceof Group g && g.isEvent() ? "The event " : "The group ") + name + " accepted your request to join";
			case PHOTO_TAG -> name + " tagged you in a photo";
			case EXPORT_READY -> "Your data export is ready to download";
		};
	}

	private static String noun(Object object) {
		return switch (object) {
			case Post post -> post.getReplyLevel() == 0 ? "post" : "comment";
			case Comment comment -> "comment";
			case Photo photo -> "photo";
			case null, default -> "post";
		};
	}

	private static String quote(Object object) {
		if (!(object instanceof PostLikeObject post))
			return "";
		String preview = markdown(post);
		if (StringUtils.isEmpty(preview))
			return "";
		return ": \"" + TextProcessor.truncateOnWordBoundary(preview.replaceAll("\\s+", " "), PREVIEW_LENGTH) + "\"";
	}

	private static String markdown(PostLikeObject post) {
		if (StringUtils.isNotEmpty(post.contentWarning) && StringUtils.isEmpty(post.text))
			return post.contentWarning;
		String text = StringUtils.isEmpty(post.text) ? "" : HtmlToMarkdown.convert(post.text);
		return StringUtils.isNotEmpty(post.contentWarning) ? "[CW: " + post.contentWarning + "]\n" + text : text;
	}
}
