package smithereen.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * What a tool call returns to the client: a text block the model reads, the same data as {@code structuredContent},
 * and whether it is an error the model should react to (as opposed to a protocol error, which is a JSON-RPC error).
 */
public record ToolResult(JsonElement structured, String text, boolean isError) {
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	/** Keys whose string values the API returns as HTML; the model gets them as Markdown instead. */
	private static final Set<String> HTML_KEYS = Set.of("text", "body", "description", "about");
	/** Keys that only matter to a UI and would waste the model's context. */
	private static final Set<String> DROPPED_KEYS = Set.of("ap_id", "post_source", "blurhash");
	/** Keys whose numeric values are Unix timestamps; the model gets ISO 8601 in the user's time zone instead. */
	private static final Set<String> DATE_KEYS = Set.of("date", "start_date", "finish_date", "tag_created", "created_at", "updated_at", "edited_at", "last_active");
	/** Keys of a wall post that make no sense on a comment and only add noise. */
	private static final Set<String> NOT_FOR_COMMENTS = Set.of("reposts", "can_pin", "is_pinned");
	/** Of the resized variants of a photo, the one an agent gets: the largest that is still comfortably small. */
	private static final String PREFERRED_PHOTO_SIZE = "y"; // 800 px

	/** Wraps an API response. Arrays and scalars are put under {@code result} because structured content must be an object. */
	public static ToolResult ofApi(JsonElement apiResult, ZoneId zone) {
		JsonElement cleaned = cleanForModel(apiResult, zone);
		JsonObject structured;
		if (cleaned instanceof JsonObject obj) {
			structured = obj;
		} else {
			structured = new JsonObject();
			structured.add("result", cleaned);
		}
		return new ToolResult(structured, PRETTY.toJson(cleaned), false);
	}

	public static ToolResult ofApi(JsonElement apiResult) {
		return ofApi(apiResult, ZoneOffset.UTC);
	}

	public static ToolResult text(String text) {
		JsonObject structured = new JsonObject();
		structured.addProperty("result", text);
		return new ToolResult(structured, text, false);
	}

	public static ToolResult error(String code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject structured = new JsonObject();
		structured.add("error", error);
		return new ToolResult(structured, code + ": " + message, true);
	}

	public JsonObject toJson() {
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		JsonObject textBlock = new JsonObject();
		textBlock.addProperty("type", "text");
		textBlock.addProperty("text", text);
		content.add(textBlock);
		result.add("content", content);
		if (structured != null && structured.isJsonObject())
			result.add("structuredContent", structured);
		if (isError)
			result.addProperty("isError", true);
		return result;
	}

	/**
	 * Recursively converts an API response for a language model: HTML becomes Markdown, timestamps become ISO 8601,
	 * a photo's size variants become one URL, nulls and UI-only keys are dropped.
	 */
	static JsonElement cleanForModel(JsonElement element, ZoneId zone) {
		return switch (element) {
			case JsonObject obj -> {
				JsonObject out = new JsonObject();
				boolean isComment = obj.has("parent_stack") || obj.has("reply_to_comment");
				boolean isPhoto = obj.get("sizes") instanceof JsonArray;
				for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
					String key = e.getKey();
					JsonElement value = e.getValue();
					if (DROPPED_KEYS.contains(key) || value == null || value.isJsonNull())
						continue;
					if (isComment && NOT_FOR_COMMENTS.contains(key))
						continue;
					if (isPhoto && key.equals("sizes")) {
						addPhotoSize(out, value.getAsJsonArray());
					} else if (HTML_KEYS.contains(key) && value instanceof JsonPrimitive p && p.isString() && p.getAsString().indexOf('<') != -1) {
						out.addProperty(key, HtmlToMarkdown.convert(p.getAsString()));
					} else if (DATE_KEYS.contains(key) && value instanceof JsonPrimitive p && p.isNumber() && p.getAsLong() > 0) {
						out.addProperty(key, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(p.getAsLong()).atZone(zone)));
					} else {
						out.add(key, cleanForModel(value, zone));
					}
				}
				yield out;
			}
			case JsonArray arr -> {
				JsonArray out = new JsonArray();
				for (JsonElement item : arr)
					out.add(cleanForModel(item, zone));
				yield out;
			}
			case null -> JsonNull.INSTANCE;
			default -> element;
		};
	}

	/** Replaces the list of resized variants with one {@code image_url} plus its dimensions. */
	private static void addPhotoSize(JsonObject out, JsonArray sizes) {
		JsonObject chosen = null;
		for (JsonElement s : sizes) {
			if (!(s instanceof JsonObject size) || !size.has("url"))
				continue;
			if (chosen == null || PREFERRED_PHOTO_SIZE.equals(stringOf(size, "type")))
				chosen = size;
			if (PREFERRED_PHOTO_SIZE.equals(stringOf(size, "type")))
				break;
		}
		if (chosen == null)
			return;
		out.add("image_url", chosen.get("url"));
		if (chosen.has("width"))
			out.add("image_width", chosen.get("width"));
		if (chosen.has("height"))
			out.add("image_height", chosen.get("height"));
	}

	private static String stringOf(JsonObject obj, String key) {
		return obj.get(key) instanceof JsonPrimitive p ? p.getAsString() : null;
	}
}
