package smithereen.mcp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;

import java.util.Map;

import smithereen.text.TextProcessor;

/**
 * What a tool call returns to the client: a text block the model reads, the same data as {@code structuredContent},
 * and whether it is an error the model should react to (as opposed to a protocol error, which is a JSON-RPC error).
 */
public record ToolResult(JsonElement structured, String text, boolean isError) {
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	/** Keys whose string values the API returns as HTML; the model gets them as plain text instead. */
	private static final java.util.Set<String> HTML_KEYS = java.util.Set.of("text", "body", "description", "about");
	/** Keys that only matter to a UI and would waste the model's context. */
	private static final java.util.Set<String> DROPPED_KEYS = java.util.Set.of("ap_id", "post_source");

	/** Wraps an API response. Arrays and scalars are put under {@code result} because structured content must be an object. */
	public static ToolResult ofApi(JsonElement apiResult) {
		JsonElement cleaned = cleanForModel(apiResult);
		JsonObject structured;
		if (cleaned instanceof JsonObject obj) {
			structured = obj;
		} else {
			structured = new JsonObject();
			structured.add("result", cleaned);
		}
		return new ToolResult(structured, PRETTY.toJson(cleaned), false);
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
	 * Recursively converts an API response for a language model: HTML text becomes plain text with line breaks,
	 * nulls and UI-only keys are dropped.
	 */
	static JsonElement cleanForModel(JsonElement element) {
		return switch (element) {
			case JsonObject obj -> {
				JsonObject out = new JsonObject();
				for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
					if (DROPPED_KEYS.contains(e.getKey()) || e.getValue() == null || e.getValue().isJsonNull())
						continue;
					JsonElement value = e.getValue();
					if (HTML_KEYS.contains(e.getKey()) && value instanceof JsonPrimitive p && p.isString() && p.getAsString().indexOf('<') != -1) {
						out.addProperty(e.getKey(), TextProcessor.stripHTML(p.getAsString(), true).strip());
					} else {
						out.add(e.getKey(), cleanForModel(value));
					}
				}
				yield out;
			}
			case JsonArray arr -> {
				JsonArray out = new JsonArray();
				for (JsonElement item : arr)
					out.add(cleanForModel(item));
				yield out;
			}
			case null -> JsonNull.INSTANCE;
			default -> element;
		};
	}
}
