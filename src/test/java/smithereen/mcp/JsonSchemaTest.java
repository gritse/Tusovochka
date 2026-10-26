package smithereen.mcp;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JsonSchemaTest {
	@Test
	public void buildsAFlatObjectSchema() {
		JsonSchema schema = JsonSchema.object()
				.integer("post_id", "The post")
				.string("message", "The text")
				.stringEnum("sort", "Order", "asc", "desc")
				.intList("ids", "IDs")
				.bool("flag", "A flag")
				.pagination(20)
				.required("post_id", "message");
		JsonObject json = schema.build();
		assertEquals("object", json.get("type").getAsString());
		assertFalse(json.get("additionalProperties").getAsBoolean());
		JsonObject props = json.getAsJsonObject("properties");
		assertEquals("integer", props.getAsJsonObject("post_id").get("type").getAsString());
		assertEquals("The post", props.getAsJsonObject("post_id").get("description").getAsString());
		assertEquals(2, props.getAsJsonObject("sort").getAsJsonArray("enum").size());
		assertEquals("integer", props.getAsJsonObject("ids").getAsJsonObject("items").get("type").getAsString());
		assertEquals(JsonSchema.MAX_COUNT, props.getAsJsonObject("count").get("maximum").getAsInt());
		assertEquals(List.of("post_id", "message", "sort", "ids", "flag", "offset", "count"), schema.propertyNames());
		assertEquals(List.of("post_id", "message"), schema.requiredNames());
		assertEquals(2, json.getAsJsonArray("required").size());
	}

	@Test
	public void omitsRequiredWhenNothingIsRequired() {
		assertFalse(JsonSchema.object().string("q", "query").build().has("required"));
	}

	@Test
	public void rejectsBadPropertyNames() {
		assertThrows(IllegalArgumentException.class, () -> JsonSchema.object().string("with space", "x"));
		assertThrows(IllegalArgumentException.class, () -> JsonSchema.object().string("", "x"));
		assertThrows(IllegalArgumentException.class, () -> JsonSchema.object().string("a".repeat(65), "x"));
		assertThrows(IllegalArgumentException.class, () -> JsonSchema.object().string("q", "x").string("q", "again"));
		assertThrows(IllegalArgumentException.class, () -> JsonSchema.object().string("q", "x").required("missing"));
	}

	@Test
	public void everyRegisteredToolHasAValidSchemaAndName() {
		ToolRegistry registry = ToolRegistry.withAllTools();
		assertTrue(registry.all().size() >= 40);
		for (McpTool tool : registry.all()) {
			assertTrue(tool.name().matches("^[a-z][a-z0-9_]{1,39}$"), tool.name());
			assertFalse(tool.description().isBlank(), tool.name());
			assertTrue(tool.description().length() < 4096, tool.name());
			JsonObject json = tool.toJson();
			assertEquals("object", json.getAsJsonObject("inputSchema").get("type").getAsString(), tool.name());
			assertTrue(json.getAsJsonObject("annotations").has("readOnlyHint"), tool.name());
			// A tool that only reads must not be marked destructive, and the other way round is pointless too
			assertFalse(tool.readOnly() && tool.destructive(), tool.name());
		}
	}
}
