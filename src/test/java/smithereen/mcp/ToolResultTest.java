package smithereen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

public class ToolResultTest {
	@Test
	public void convertsHtmlToMarkdownAndDropsNoise() {
		JsonObject post = JsonParser.parseString("""
				{"id": 5, "text": "<p>Hello <b>world</b></p><p>second</p>", "ap_id": "https://x/1", "owner": null, "title": "<b>kept</b>",
				 "post_source": {"app": {"id": 1}}, "nested": [{"body": "<a href='https://x'>link</a>", "n": 1}]}""").getAsJsonObject();
		JsonObject cleaned = ToolResult.cleanForModel(post, ZoneId.of("UTC")).getAsJsonObject();
		assertEquals("Hello **world**\n\nsecond", cleaned.get("text").getAsString());
		assertFalse(cleaned.has("ap_id"));
		assertFalse(cleaned.has("owner"));
		assertFalse(cleaned.has("post_source"));
		assertEquals("<b>kept</b>", cleaned.get("title").getAsString());
		assertEquals("[link](https://x)", cleaned.getAsJsonArray("nested").get(0).getAsJsonObject().get("body").getAsString());
		assertEquals(1, cleaned.getAsJsonArray("nested").get(0).getAsJsonObject().get("n").getAsInt());
	}

	@Test
	public void timestampsBecomeIsoDatesInTheUsersTimeZone() {
		JsonObject obj = JsonParser.parseString("{\"date\": 1792617333, \"start_date\": 1792617333, \"count\": 1792617333, \"finish_date\": 0}").getAsJsonObject();
		JsonObject cleaned = ToolResult.cleanForModel(obj, ZoneId.of("Europe/Warsaw")).getAsJsonObject();
		assertEquals("2026-10-21T23:15:33+02:00", cleaned.get("date").getAsString());
		assertEquals("2026-10-21T23:15:33+02:00", cleaned.get("start_date").getAsString());
		assertEquals(1792617333L, cleaned.get("count").getAsLong());
		assertEquals(0, cleaned.get("finish_date").getAsInt());
	}

	@Test
	public void photosKeepOneSizeAndCommentsLoseWallOnlyFields() {
		JsonObject photo = JsonParser.parseString("""
				{"id": "abc", "blurhash": "LKO2", "width": 4000, "height": 3000, "text": "caption",
				 "sizes": [{"type": "s", "url": "u-s", "width": 100, "height": 75}, {"type": "y", "url": "u-y", "width": 800, "height": 600}, {"type": "z", "url": "u-z", "width": 1280, "height": 960}]}""").getAsJsonObject();
		JsonObject cleaned = ToolResult.cleanForModel(photo, ZoneId.of("UTC")).getAsJsonObject();
		assertFalse(cleaned.has("sizes"));
		assertFalse(cleaned.has("blurhash"));
		assertEquals("u-y", cleaned.get("image_url").getAsString());
		assertEquals(800, cleaned.get("image_width").getAsInt());
		assertEquals("caption", cleaned.get("text").getAsString());

		JsonObject comment = JsonParser.parseString("""
				{"id": 7, "parent_stack": [5], "reply_to_comment": 6, "reposts": {"count": 0}, "can_pin": false, "is_pinned": false, "likes": {"count": 1}, "can_edit": true}""").getAsJsonObject();
		cleaned = ToolResult.cleanForModel(comment, ZoneId.of("UTC")).getAsJsonObject();
		assertFalse(cleaned.has("reposts"));
		assertFalse(cleaned.has("can_pin"));
		assertTrue(cleaned.has("likes"));
		assertTrue(cleaned.has("can_edit"));
		JsonObject post = JsonParser.parseString("{\"id\": 5, \"reposts\": {\"count\": 0}, \"can_pin\": true}").getAsJsonObject();
		assertTrue(ToolResult.cleanForModel(post, ZoneId.of("UTC")).getAsJsonObject().has("reposts"));
	}

	@Test
	public void wrapsNonObjectsUnderResult() {
		JsonArray arr = new JsonArray();
		arr.add(1);
		ToolResult result = ToolResult.ofApi(arr);
		JsonObject json = result.toJson();
		assertFalse(json.has("isError"));
		assertEquals(1, json.getAsJsonObject("structuredContent").getAsJsonArray("result").size());
		assertEquals("text", json.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
		assertTrue(json.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("1"));
	}

	@Test
	public void errorsAreMarked() {
		JsonObject json = ToolResult.error("not_found", "no such post").toJson();
		assertTrue(json.get("isError").getAsBoolean());
		assertEquals("not_found: no such post", json.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
		assertEquals("not_found", json.getAsJsonObject("structuredContent").getAsJsonObject("error").get("code").getAsString());
	}
}
