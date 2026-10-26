package smithereen.mcp;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import smithereen.Config;
import smithereen.model.Group;
import smithereen.model.MailMessage;
import smithereen.model.Post;
import smithereen.model.User;
import smithereen.model.notifications.RealtimeNotification;

import static org.junit.jupiter.api.Assertions.*;

public class AgentEventsTest {
	@BeforeAll
	public static void setUpConfig() throws Exception {
		Config.domain = "smithereen.local";
		Field localURI = Config.class.getDeclaredField("localURI");
		localURI.setAccessible(true);
		localURI.set(null, URI.create("https://smithereen.local/"));
		Arrays.fill(Config.objectIdObfuscationKeysByType, new int[]{1, 2, 3, 4});
	}

	private static User user(int id, String first, String last) {
		User u = new User();
		u.id = id;
		u.firstName = first;
		u.lastName = last;
		u.username = first.toLowerCase();
		return u;
	}

	private static Post post(int id, int ownerID, String html, Integer... replyKey) {
		Post p = new Post();
		p.id = id;
		p.ownerID = ownerID;
		p.text = html;
		p.replyKey = List.of(replyKey);
		return p;
	}

	@Test
	public void wallCommentIsAddressedByItsTopLevelPost() {
		Post parent = post(157, 7, "<p>Going to the lake on Sunday</p>");
		Post comment = post(170, 7, "<p>Count me <b>in</b></p>", 157, 168);
		JsonObject ev = AgentEvents.toJson("30", RealtimeNotification.Type.REPLY, comment, parent, user(4, "Yulia", "Ivanova"));
		assertEquals("reply", ev.get("kind").getAsString());
		assertEquals(4, ev.get("from_id").getAsInt());
		assertEquals("Yulia Ivanova", ev.get("from_name").getAsString());
		assertEquals(157, ev.get("post_id").getAsInt());
		assertEquals(170, ev.get("comment_id").getAsInt());
		assertEquals(168, ev.get("reply_to_comment").getAsInt());
		assertEquals(7, ev.get("owner_id").getAsInt());
		assertEquals("Count me **in**", ev.get("text").getAsString());
		assertEquals("Yulia Ivanova replied to your comment", ev.get("summary").getAsString());
		assertEquals("https://smithereen.local/posts/157#comment170", ev.get("url").getAsString());
	}

	@Test
	public void commentOnPostQuotesThePost() {
		Post parent = post(157, 7, "<p>Going to the lake on Sunday</p>");
		Post comment = post(170, 7, "<p>Count me in</p>", 157);
		JsonObject ev = AgentEvents.toJson("31", RealtimeNotification.Type.REPLY, comment, parent, user(4, "Yulia", "Ivanova"));
		assertEquals("Yulia Ivanova commented on your post: \"Going to the lake on Sunday\"", ev.get("summary").getAsString());
		assertFalse(ev.has("reply_to_comment"));
	}

	@Test
	public void likeCarriesNoTextButQuotesTheStart() {
		JsonObject ev = AgentEvents.toJson("32", RealtimeNotification.Type.LIKE, post(136, 1, "<p>Hello world</p>"), null, user(2, "Maria", "Petrova"));
		assertEquals("Maria Petrova liked your post: \"Hello world\"", ev.get("summary").getAsString());
		assertEquals(136, ev.get("post_id").getAsInt());
		assertFalse(ev.has("text"));
		assertFalse(ev.has("comment_id"));
	}

	@Test
	public void mailMessageHasItsStringID() {
		MailMessage msg = new MailMessage();
		msg.id = 42;
		msg.text = "<p>Are you coming tonight?</p>";
		msg.subject = "Party";
		JsonObject ev = AgentEvents.toJson("msg1", RealtimeNotification.Type.MAIL_MESSAGE, msg, null, user(2, "Maria", "Petrova"));
		assertEquals("mail_message", ev.get("kind").getAsString());
		assertEquals(msg.getIdString(), ev.get("message_id").getAsString());
		assertEquals("Party", ev.get("subject").getAsString());
		assertEquals("Are you coming tonight?", ev.get("text").getAsString());
		assertEquals("Maria Petrova sent you a private message", ev.get("summary").getAsString());
	}

	@Test
	public void groupActorHasNegativeID() {
		Group g = new Group();
		g.id = 3;
		g.name = "Hiking club";
		g.username = "hiking";
		JsonObject ev = AgentEvents.toJson("j3", RealtimeNotification.Type.GROUP_REQUEST_ACCEPTED, null, null, g);
		assertEquals(-3, ev.get("from_id").getAsInt());
		assertEquals("The group Hiking club accepted your request to join", ev.get("summary").getAsString());
		assertEquals("https://smithereen.local/hiking", ev.get("url").getAsString());
	}

	@Test
	public void friendRequestWithoutObjectLinksToTheProfile() {
		JsonObject ev = AgentEvents.toJson("f1", RealtimeNotification.Type.FRIEND_REQUEST, null, null, user(9, "Dmitry", "Smirnov"));
		assertEquals("Dmitry Smirnov sent you a friend request", ev.get("summary").getAsString());
		assertEquals("https://smithereen.local/dmitry", ev.get("url").getAsString());
		assertFalse(ev.has("text"));
	}
}
