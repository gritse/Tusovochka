package smithereen.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.http.ExtendedHttpClient;
import smithereen.lang.Lang;
import smithereen.model.Account;
import smithereen.model.Group;
import smithereen.model.OwnedContentObject;
import smithereen.model.Post;
import smithereen.model.PostLikeObject;
import smithereen.model.SizedImage;
import smithereen.model.User;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.comments.CommentableContentObject;
import smithereen.model.feed.CommentsNewsfeedObjectType;
import smithereen.model.feed.NewsfeedEntry;
import smithereen.model.groups.GroupFeatureState;
import smithereen.model.notifications.PushNotificationType;
import smithereen.model.notifications.RealtimeNotification;
import smithereen.model.notifications.RealtimeNotificationSettingType;
import smithereen.model.notifications.WebPushSubscription;
import smithereen.model.objects.Actor;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.storage.WebPushStorage;
import smithereen.text.TextProcessor;
import smithereen.util.BackgroundTaskRunner;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.WebPush;
import spark.utils.StringUtils;

/**
 * Browser push notifications (Web Push with VAPID). Every notification the site shows goes out through
 * {@link #sendForNotification}, and news that would appear in the three tabs of the feed through the
 * {@code sendFor*Feed*} methods, each subject to the user's {@link PushNotificationType} settings. The service
 * worker ({@code /sw.js}) shows the title and body and opens the URL when the notification is tapped.
 */
public class WebPushController {
	private static final Logger LOG = LoggerFactory.getLogger(WebPushController.class);
	/**
	 * The push services of the browsers we know. The server POSTs to whatever endpoint a browser gives it, so endpoints
	 * are limited to these hosts rather than letting a user point the server at arbitrary URLs.
	 */
	private static final Set<String> PUSH_SERVICE_HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com");
	// Chromium builds without Google API keys get endpoints like jmt17.google.com instead of fcm.googleapis.com
	private static final List<String> PUSH_SERVICE_HOST_SUFFIXES = List.of(".google.com", ".googleapis.com", ".push.apple.com", ".notify.windows.com");
	/**
	 * Uploading an album or adding several friends makes one feed entry per photo or friend. The feed groups them by
	 * day; push sends one notification and then stays quiet about the same kind of news from the same author for this long.
	 */
	private static final Duration FEED_PUSH_COOLDOWN = Duration.ofMinutes(15);
	private static final int PREVIEW_LENGTH = 80;

	private final ApplicationContext context;
	private final HttpClient httpClient = ExtendedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ConcurrentHashMap<String, Instant> recentFeedPushes = new ConcurrentHashMap<>();
	/**
	 * Account and feed item key of recent pushes, so an item that is in two feeds reaches a user once.
	 */
	private final ConcurrentHashMap<String, Instant> recentDeliveries = new ConcurrentHashMap<>();

	public WebPushController(ApplicationContext context) {
		this.context = context;
	}

	/**
	 * What happened to one subscription when a notification was sent to it.
	 *
	 * @param status  the push service's HTTP status, or 0 if the request did not get through
	 * @param removed whether the push service said the subscription is gone, so it was deleted
	 */
	public record DeliveryResult(WebPushSubscription subscription, int status, String error, boolean removed) {
		public boolean delivered() {
			return status / 100 == 2;
		}
	}

	/**
	 * One notification as the service worker shows it. {@code icon} is the avatar of whoever caused it, or null.
	 */
	private record Message(String title, String body, String url, String icon) {}

	// region Subscriptions

	public static boolean isAllowedEndpoint(String endpoint) {
		try {
			URI uri = new URI(endpoint);
			if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1)
				return false;
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			return PUSH_SERVICE_HOSTS.contains(host) || PUSH_SERVICE_HOST_SUFFIXES.stream().anyMatch(host::endsWith);
		} catch (URISyntaxException x) {
			return false;
		}
	}

	public void subscribe(Account self, String endpoint, String p256dh, String auth, String userAgent) {
		if (StringUtils.isEmpty(endpoint) || endpoint.length() > 1000 || !isAllowedEndpoint(endpoint))
			throw new BadRequestException("Unsupported push service");
		byte[] key, secret;
		try {
			key = Base64.getUrlDecoder().decode(p256dh);
			secret = Base64.getUrlDecoder().decode(auth);
			WebPush.decodePublicKey(key);
		} catch (IllegalArgumentException | NullPointerException | GeneralSecurityException x) {
			throw new BadRequestException("Invalid subscription keys");
		}
		if (secret.length != 16)
			throw new BadRequestException("Invalid subscription keys");
		if (userAgent != null && userAgent.length() > 300)
			userAgent = userAgent.substring(0, 300);
		try {
			WebPushStorage.putSubscription(self.id, endpoint, key, secret, userAgent);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	public void unsubscribe(Account self, String endpoint) {
		try {
			WebPushStorage.deleteSubscription(self.id, endpoint);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	public List<WebPushSubscription> getSubscriptions(Account self) {
		try {
			return WebPushStorage.getSubscriptions(self.id);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	public void setPushTypes(Account self, Set<PushNotificationType> types) {
		self.prefs.pushTypes = types.isEmpty() ? EnumSet.noneOf(PushNotificationType.class) : EnumSet.copyOf(types);
		context.getUsersController().updateUserPreferences(self);
	}

	// endregion

	// region Sending

	/**
	 * Sends a notification to every device of an account and waits for the push services to answer. Used by the
	 * test button in Settings, so it ignores the user's type settings.
	 */
	public List<DeliveryResult> sendToAccount(Account account, String title, String body, String url) {
		return deliver(account, getSubscriptions(account), new Message(title, body, url, null));
	}

	/**
	 * Pushes a notification the site has just shown the user, worded the same way as in the on-site notifier.
	 */
	public void sendForNotification(Account account, RealtimeNotification.Type type, Object object, OwnedContentObject relatedObject, Actor actor) {
		RealtimeNotificationSettingType settingType = type.getSettingType();
		if (settingType != null && !account.prefs.getPushTypes().contains(PushNotificationType.forRealtimeType(settingType)))
			return;
		BackgroundTaskRunner.getInstance().submit(() -> {
			try {
				List<WebPushSubscription> subs = getSubscriptions(account);
				if (subs.isEmpty())
					return;
				Lang l = Lang.get(account.prefs.locale);
				RealtimeNotification.ObjectType objType = object instanceof OwnedContentObject owned ? context.getNotificationsController().getRealtimeNotificationObjectTypeForObject(owned) : null;
				NotificationsController.NotificationTexts texts = context.getNotificationsController().buildNotificationTexts(type, objType, object, relatedObject, actor,
						l, timeZoneFor(account), account.prefs.notifierShowMessageText);
				String title = texts.title();
				String body = TextProcessor.stripHTML(texts.content(), false);
				// Like a messaging app: who did it as the title, what they wrote or did as the body. The notifier's
				// content always starts with the actor's name, as a link.
				if (actor != null) {
					String name = actor.getName();
					if (body.startsWith(name))
						body = body.substring(name.length()).strip();
					if (!body.isEmpty())
						title = name;
				}
				deliver(account, subs, new Message(title, body, texts.url(), avatarURL(actor)));
			} catch (Exception x) {
				LOG.warn("Failed to push a {} notification to account {}", type, account.id, x);
			}
		});
	}

	/**
	 * Pushes a new entry of the friends feed to the followers of its author, the way the feed itself would show it
	 * to them: honoring the author's "what to show in the news" setting, the per-post and wall privacy, and blocks.
	 */
	public void sendForFriendsFeedEntry(User author, long objectID, NewsfeedEntry.Type type) {
		PushNotificationType pushType = PushNotificationType.forFriendsFeedEntry(type);
		if (pushType == null)
			return;
		if (type != NewsfeedEntry.Type.POST && author.newsTypesToShow != null && !author.newsTypesToShow.contains(pushType.getFriendsFeedFilter()))
			return;
		if (isGroupable(type) && isInCooldown("friends:" + author.id + ":" + type))
			return;
		BackgroundTaskRunner.getInstance().submit(() -> {
			try {
				Map<Integer, Account> recipients = findRecipients(UserStorage.getFollowerIDsForFeed(author.id), pushType);
				if (recipients.isEmpty())
					return;
				FeedItem item = loadFriendsFeedItem(author, objectID, type);
				if (item == null)
					return;
				fanOut(recipients, author, item);
			} catch (Exception x) {
				LOG.warn("Failed to push friends feed entry {} {} by user {}", type, objectID, author.id, x);
			}
		});
	}

	/**
	 * Pushes a new entry of the groups feed to the members of the group.
	 */
	public void sendForGroupsFeedEntry(Group group, long objectID, NewsfeedEntry.Type type) {
		PushNotificationType pushType = PushNotificationType.forGroupsFeedEntry(type);
		if (pushType == null)
			return;
		if (isGroupable(type) && isInCooldown("group:" + group.id + ":" + type))
			return;
		BackgroundTaskRunner.getInstance().submit(() -> {
			try {
				Map<Integer, Account> recipients = findRecipients(context.getGroupsController().getAllMemberIDs(group), pushType);
				if (recipients.isEmpty())
					return;
				FeedItem item = loadGroupsFeedItem(group, objectID, type);
				if (item == null)
					return;
				fanOut(recipients, group, item);
			} catch (Exception x) {
				LOG.warn("Failed to push groups feed entry {} {} in group {}", type, objectID, group.id, x);
			}
		});
	}

	/**
	 * Pushes a new comment to everyone else who has commented on the same post, photo or topic, i.e. to those who see
	 * it in the comments tab of the feed. Those who got a notification of their own about it (the one replied to,
	 * the mentioned) are skipped so nobody is told twice.
	 *
	 * @param comment  the new comment, a {@link Post} reply or a {@link Comment}
	 * @param parent   the top-level post, or the photo or topic the comment is on
	 */
	public void sendForCommentsFeed(PostLikeObject comment, Object parent, User author, Set<Integer> alreadyNotified) {
		CommentsNewsfeedObjectType feedType;
		long parentID;
		switch (parent) {
			case Post post -> {
				feedType = CommentsNewsfeedObjectType.POST;
				parentID = post.id;
			}
			case CommentableContentObject cco -> {
				feedType = cco.getCommentParentID().type().newsfeedType();
				parentID = cco.getCommentParentID().id();
			}
			default -> {
				return;
			}
		}
		if (feedType == null)
			return;
		BackgroundTaskRunner.getInstance().submit(() -> {
			try {
				List<Integer> participants = new ArrayList<>(PostStorage.getCommentsFeedParticipants(feedType, parentID));
				participants.removeIf(id -> id == author.id || alreadyNotified.contains(id));
				Map<Integer, Account> recipients = findRecipients(participants, PushNotificationType.COMMENTS);
				if (recipients.isEmpty())
					return;
				String preview = textPreview(comment);
				String url = switch (parent) {
					case Post post -> post.getInternalURL() + "#comment" + ((Post) comment).id;
					case CommentableContentObject cco -> cco.getURL() + "#comment" + ((Comment) comment).getIDString();
					default -> throw new IllegalStateException();
				};
				FeedItem item = new FeedItem("comment:" + comment.getObjectID(), Set.of(author.id),
						(l, tz) -> new Message(author.getFullName(), StringUtils.isEmpty(preview) ? l.get("push_new_comment") : preview, url, avatarURL(author)), null);
				fanOut(recipients, author, item);
			} catch (Exception x) {
				LOG.warn("Failed to push comment {} on {} {}", comment.getObjectID(), feedType, parentID, x);
			}
		});
	}

	/**
	 * A feed entry ready to be pushed: how to word it in a given language, and whether a given recipient may see it.
	 * {@code key} identifies the underlying object: a topic is news in both the friends and the groups feed, and a
	 * user who is a friend of its author and a member of its group must still get it only once. {@code skipUserIDs} are
	 * the people who did the thing, and those already told about it by a notification of their own (the mentioned);
	 * they may well be among the recipients (a group member who posted in the group, a follower of the friend on
	 * whose wall they wrote) and are not told again.
	 */
	private record FeedItem(String key, Set<Integer> skipUserIDs, BiFunction<Lang, ZoneId, Message> texts, Function<User, Boolean> visibleTo) {}

	private FeedItem loadFriendsFeedItem(User author, long objectID, NewsfeedEntry.Type type) {
		return switch (type) {
			case POST -> {
				Post post = context.getWallController().getPostOrThrow((int) objectID);
				User postAuthor = post.authorID == author.id ? author : context.getUsersController().getUserOrThrow(post.authorID);
				String preview = textPreview(post);
				String url = post.getInternalURL().toString();
				HashSet<Integer> skip = new HashSet<>(post.mentionedUserIDs);
				skip.add(post.authorID);
				yield new FeedItem("post:" + post.id, skip, (l, tz) -> {
					String body;
					if (post.ownerID != post.authorID) {
						// Someone else wrote on the friend's wall: that is news about the friend, as in the feed
						body = l.get("push_feed_wall_to_wall", Map.of("name", author.getFullName(), "gender", postAuthor.gender)) + (StringUtils.isEmpty(preview) ? "" : ": " + preview);
					} else {
						body = StringUtils.isEmpty(preview) ? l.get("push_feed_new_post") : preview;
					}
					return new Message(postAuthor.getFullName(), body, url, avatarURL(postAuthor));
				}, recipient -> {
					ArrayList<Post> posts = new ArrayList<>(List.of(post));
					context.getPrivacyController().filterPostsForPerPostPrivacy(recipient, posts);
					context.getPrivacyController().filterPostsForOwnerPrivacy(recipient, posts);
					return !posts.isEmpty();
				});
			}
			case ADD_PHOTO, PHOTO_TAG -> {
				Photo photo = context.getPhotosController().getPhotoIgnoringPrivacy(objectID);
				PhotoAlbum album = context.getPhotosController().getAlbumIgnoringPrivacy(photo.albumID);
				// A tag in a group's album: visible to whoever can see the group's photos, not governed by a user privacy setting
				Group albumGroup = album.ownerID < 0 ? context.getGroupsController().getGroupOrThrow(-album.ownerID) : null;
				User albumOwner = album.ownerID == author.id ? author : album.ownerID > 0 ? context.getUsersController().getUserOrThrow(album.ownerID) : null;
				String url = photo.getURL();
				yield new FeedItem(type + ":" + photo.id, Set.of(author.id, photo.authorID), (l, tz) -> new Message(author.getFullName(),
						l.get(type == NewsfeedEntry.Type.ADD_PHOTO ? "push_feed_added_photo" : "push_feed_tagged_in_photo", Map.of("album", album.title, "gender", author.gender)),
						url, avatarURL(author)),
						recipient -> {
							if (albumGroup != null)
								return albumGroup.photosState != GroupFeatureState.DISABLED && (albumGroup.accessType == Group.AccessType.OPEN || context.getPrivacyController().canUserAccessGroupContent(recipient, albumGroup));
							return context.getPrivacyController().checkUserPrivacy(recipient, albumOwner, album.viewPrivacy);
						});
			}
			case BOARD_TOPIC -> {
				BoardTopic topic = context.getBoardController().getTopicIgnoringPrivacy(objectID);
				Group group = context.getGroupsController().getGroupOrThrow(topic.groupID);
				yield new FeedItem("topic:" + topic.id, Set.of(topic.authorID), (l, tz) -> new Message(author.getFullName(),
						l.get("push_feed_created_topic", Map.of("group", group.name, "topic", topic.title, "gender", author.gender)), topic.getURL(), avatarURL(author)),
						recipient -> group.accessType == Group.AccessType.OPEN || context.getPrivacyController().canUserAccessGroupContent(recipient, group));
			}
			case ADD_FRIEND -> {
				User friend = context.getUsersController().getUserOrThrow((int) objectID);
				yield new FeedItem("friend:" + author.id + ":" + friend.id, Set.of(author.id, friend.id), (l, tz) -> new Message(author.getFullName(),
						l.get("push_feed_added_friend", Map.of("name", friend.getFullName(), "gender", author.gender)), friend.getProfileURL(), avatarURL(author)), null);
			}
			case JOIN_GROUP, JOIN_EVENT, CREATE_GROUP, CREATE_EVENT -> {
				Group group = context.getGroupsController().getGroupOrThrow((int) objectID);
				String key = switch (type) {
					case JOIN_GROUP -> "push_feed_joined_group";
					case JOIN_EVENT -> "push_feed_joined_event";
					case CREATE_GROUP -> "push_feed_created_group";
					default -> "push_feed_created_event";
				};
				yield new FeedItem(type + ":" + author.id + ":" + group.id, Set.of(author.id), (l, tz) -> new Message(author.getFullName(),
						l.get(key, Map.of("group", group.name, "gender", author.gender)), group.getProfileURL(), avatarURL(author)), null);
			}
			case RELATIONSHIP_STATUS -> new FeedItem("relationship:" + author.id + ":" + objectID, Set.of(author.id), (l, tz) -> new Message(author.getFullName(),
					l.get("push_feed_relationship", Map.of("gender", author.gender)), author.getProfileURL(), avatarURL(author)), null);
			default -> null;
		};
	}

	private FeedItem loadGroupsFeedItem(Group group, long objectID, NewsfeedEntry.Type type) {
		return switch (type) {
			case POST -> {
				Post post = context.getWallController().getPostOrThrow((int) objectID);
				User postAuthor = context.getUsersController().getUserOrThrow(post.authorID);
				String preview = textPreview(post);
				String url = post.getInternalURL().toString();
				HashSet<Integer> skip = new HashSet<>(post.mentionedUserIDs);
				skip.add(post.authorID);
				yield new FeedItem("post:" + post.id, skip, (l, tz) -> new Message(group.name,
						StringUtils.isEmpty(preview) ? l.get("push_feed_new_post_by", Map.of("name", postAuthor.getFullName())) : postAuthor.getFullName() + ": " + preview,
						url, avatarURL(group)), null);
			}
			case BOARD_TOPIC -> {
				BoardTopic topic = context.getBoardController().getTopicIgnoringPrivacy(objectID);
				User topicAuthor = context.getUsersController().getUserOrThrow(topic.authorID);
				yield new FeedItem("topic:" + topic.id, Set.of(topic.authorID), (l, tz) -> new Message(group.name,
						l.get("push_group_new_topic", Map.of("name", topicAuthor.getFullName(), "topic", topic.title, "gender", topicAuthor.gender)), topic.getURL(), avatarURL(group)), null);
			}
			case ADD_PHOTO -> {
				Photo photo = context.getPhotosController().getPhotoIgnoringPrivacy(objectID);
				PhotoAlbum album = context.getPhotosController().getAlbumIgnoringPrivacy(photo.albumID);
				yield new FeedItem("ADD_PHOTO:" + photo.id, Set.of(photo.authorID), (l, tz) -> new Message(group.name, l.get("push_group_added_photo", Map.of("album", album.title)), photo.getURL(), avatarURL(group)), null);
			}
			default -> null;
		};
	}

	/**
	 * Of the given users, those who have a device and want this kind of notification, keyed by user ID.
	 */
	private Map<Integer, Account> findRecipients(Collection<Integer> userIDs, PushNotificationType type) throws SQLException {
		if (userIDs.isEmpty())
			return Map.of();
		Map<Integer, Integer> accountIDs = WebPushStorage.getAccountIDsWithSubscriptions(userIDs);
		if (accountIDs.isEmpty())
			return Map.of();
		HashMap<Integer, Account> result = new HashMap<>();
		for (Account acc : UserStorage.getAccounts(accountIDs.values()).values()) {
			if (acc.user != null && acc.prefs.getPushTypes().contains(type))
				result.put(acc.user.id, acc);
		}
		return result;
	}

	/**
	 * Sends one feed item to every recipient who may see it, wording it once per language.
	 */
	private void fanOut(Map<Integer, Account> recipients, Actor source, FeedItem item) {
		if (recentDeliveries.size() > 10000) {
			Instant cutoff = Instant.now().minus(FEED_PUSH_COOLDOWN);
			recentDeliveries.values().removeIf(t -> t.isBefore(cutoff));
		}
		HashMap<Lang, Message> messages = new HashMap<>();
		for (Account account : recipients.values()) {
			if (item.skipUserIDs().contains(account.user.id))
				continue;
			try {
				if (source instanceof User sourceUser && (context.getPrivacyController().isUserBlocked(account.user, sourceUser) || context.getPrivacyController().isUserBlocked(sourceUser, account.user)))
					continue;
				if (item.visibleTo() != null && !item.visibleTo().apply(account.user))
					continue;
				if (recentDeliveries.put(account.id + ":" + item.key(), Instant.now()) != null)
					continue;
				List<WebPushSubscription> subs = getSubscriptions(account);
				if (subs.isEmpty())
					continue;
				Lang l = Lang.get(account.prefs.locale);
				Message msg = messages.computeIfAbsent(l, lang -> item.texts().apply(lang, timeZoneFor(account)));
				deliver(account, subs, msg);
			} catch (ObjectNotFoundException x) {
				LOG.debug("Skipping push to account {}: {}", account.id, x.getMessage());
			} catch (Exception x) {
				LOG.warn("Failed to push to account {}", account.id, x);
			}
		}
	}

	private List<DeliveryResult> deliver(Account account, List<WebPushSubscription> subs, Message msg) {
		byte[] payload = new JsonObjectBuilder()
				.add("title", msg.title())
				.add("body", msg.body())
				.add("url", msg.url())
				.add("icon", msg.icon())
				.build()
				.toString()
				.getBytes(StandardCharsets.UTF_8);
		List<DeliveryResult> results = new ArrayList<>();
		for (WebPushSubscription sub : subs) {
			DeliveryResult r = send(sub, payload);
			LOG.debug("Push to account {} device {}: {} [{} | {} | {}]", account.id, sub.id(), r.delivered() ? "sent" : r.status() + " " + r.error(), msg.title(), msg.body(), msg.url());
			results.add(r);
		}
		return results;
	}

	private DeliveryResult send(WebPushSubscription sub, byte[] payload) {
		try {
			URI endpoint = URI.create(sub.endpoint());
			HttpRequest req = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(15))
					.header("Authorization", WebPush.vapidAuthorization(endpoint, Config.webPushPrivateKey, Config.webPushPublicKey, vapidSubject(),
							Instant.now().plus(12, ChronoUnit.HOURS)))
					.header("Content-Encoding", "aes128gcm")
					.header("Content-Type", "application/octet-stream")
					.header("TTL", String.valueOf(24 * 60 * 60))
					.header("Urgency", "high")
					.POST(HttpRequest.BodyPublishers.ofByteArray(WebPush.encrypt(payload, sub.p256dh(), sub.auth())))
					.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			int status = resp.statusCode();
			if (status / 100 == 2)
				return new DeliveryResult(sub, status, null, false);
			LOG.debug("Push service returned {} for subscription {}: {}", status, sub.id(), resp.body());
			// 404 and 410 mean the browser unsubscribed or the subscription expired
			boolean gone = status == 404 || status == 410;
			if (gone)
				WebPushStorage.deleteSubscription(sub.id());
			String error = resp.body();
			if (error != null && error.length() > 300)
				error = error.substring(0, 300);
			return new DeliveryResult(sub, status, error, gone);
		} catch (IOException | GeneralSecurityException | SQLException | IllegalArgumentException x) {
			LOG.debug("Failed to send a push notification to subscription {}", sub.id(), x);
			return new DeliveryResult(sub, 0, x.toString(), false);
		} catch (InterruptedException x) {
			Thread.currentThread().interrupt();
			return new DeliveryResult(sub, 0, x.toString(), false);
		}
	}

	// endregion

	private static boolean isGroupable(NewsfeedEntry.Type type) {
		return type == NewsfeedEntry.Type.ADD_PHOTO || type == NewsfeedEntry.Type.PHOTO_TAG || type == NewsfeedEntry.Type.ADD_FRIEND
				|| type == NewsfeedEntry.Type.JOIN_GROUP || type == NewsfeedEntry.Type.JOIN_EVENT;
	}

	/**
	 * Records a push for the key and says whether the previous one was too recent.
	 */
	private boolean isInCooldown(String key) {
		Instant now = Instant.now();
		Instant last = recentFeedPushes.put(key, now);
		if (recentFeedPushes.size() > 1000)
			recentFeedPushes.values().removeIf(t -> t.isBefore(now.minus(FEED_PUSH_COOLDOWN)));
		return last != null && last.isAfter(now.minus(FEED_PUSH_COOLDOWN));
	}

	private static ZoneId timeZoneFor(Account account) {
		return Objects.requireNonNullElse(account.prefs.timeZone, ZoneId.systemDefault());
	}

	private static String avatarURL(Actor actor) {
		if (actor == null || !actor.hasAvatar())
			return null;
		return actor.getAvatar().getUriForSizeAndFormat(SizedImage.Type.AVA_SQUARE_MEDIUM, SizedImage.Format.JPEG).toString();
	}

	/**
	 * The first words of a post or comment as plain text, or its content warning if it has one.
	 */
	private static String textPreview(PostLikeObject post) {
		if (StringUtils.isNotEmpty(post.contentWarning))
			return TextProcessor.truncateOnWordBoundary(post.contentWarning, PREVIEW_LENGTH);
		if (StringUtils.isEmpty(post.text))
			return "";
		return TextProcessor.truncateOnWordBoundary(TextProcessor.stripHTML(post.text, false), PREVIEW_LENGTH);
	}

	/**
	 * The contact the push services see in the VAPID token. Apple refuses tokens without a valid {@code mailto:} or
	 * {@code https:} subject.
	 */
	private static String vapidSubject() {
		if (StringUtils.isNotEmpty(Config.serverAdminEmail))
			return "mailto:" + Config.serverAdminEmail;
		return "https://" + Config.domain;
	}
}
