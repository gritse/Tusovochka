package smithereen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ToolResultTest {
	@Test
	public void stripsHtmlFromTextFieldsAndDropsNoise() {
		JsonObject post = JsonParser.parseString("""
				{"id": 5, "text": "<p>Hello <b>world</b></p><p>second</p>", "ap_id": "https://x/1", "owner": null, "title": "<b>kept</b>",
				 "nested": [{"text": "<a href='x'>link</a>", "n": 1}]}""").getAsJsonObject();
		JsonObject cleaned = ToolResult.cleanForModel(post).getAsJsonObject();
		assertEquals("Hello world\n\nsecond", cleaned.get("text").getAsString().replace("\r", ""));
		assertFalse(cleaned.has("ap_id"));
		assertFalse(cleaned.has("owner"));
		assertEquals("<b>kept</b>", cleaned.get("title").getAsString());
		assertEquals("link", cleaned.getAsJsonArray("nested").get(0).getAsJsonObject().get("text").getAsString());
		assertEquals(1, cleaned.getAsJsonArray("nested").get(0).getAsJsonObject().get("n").getAsInt());
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
