package smithereen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A small builder for the JSON Schema (draft 2020-12 subset) that describes a tool's input. Keeps the schemas flat and
 * within what MCP clients accept: property names in {@code [A-Za-z0-9_.-]}, no combinators at the root.
 */
public final class JsonSchema {
	/** What the Claude API accepts as a top-level property name. */
	private static final Pattern PROPERTY_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
	public static final int MAX_COUNT = 50;

	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();
	private final List<String> names = new ArrayList<>();
	private final List<String> requiredNames = new ArrayList<>();

	private JsonSchema() {}

	public static JsonSchema object() {
		return new JsonSchema();
	}

	private JsonSchema add(String name, String type, String description) {
		if (!PROPERTY_NAME.matcher(name).matches())
			throw new IllegalArgumentException("Invalid property name " + name);
		if (properties.has(name))
			throw new IllegalArgumentException("Duplicate property " + name);
		JsonObject prop = new JsonObject();
		prop.addProperty("type", type);
		prop.addProperty("description", description);
		properties.add(name, prop);
		names.add(name);
		return this;
	}

	private JsonObject last() {
		return properties.getAsJsonObject(names.getLast());
	}

	public JsonSchema string(String name, String description) {
		return add(name, "string", description);
	}

	public JsonSchema stringEnum(String name, String description, String... values) {
		add(name, "string", description);
		JsonArray arr = new JsonArray();
		for (String v : values)
			arr.add(v);
		last().add("enum", arr);
		return this;
	}

	public JsonSchema integer(String name, String description) {
		return add(name, "integer", description);
	}

	public JsonSchema integer(String name, String description, int min, int max) {
		add(name, "integer", description);
		last().addProperty("minimum", min);
		last().addProperty("maximum", max);
		return this;
	}

	public JsonSchema bool(String name, String description) {
		return add(name, "boolean", description);
	}

	private JsonSchema list(String name, String itemType, String description) {
		add(name, "array", description);
		JsonObject items = new JsonObject();
		items.addProperty("type", itemType);
		last().add("items", items);
		return this;
	}

	public JsonSchema intList(String name, String description) {
		return list(name, "integer", description);
	}

	public JsonSchema stringList(String name, String description) {
		return list(name, "string", description);
	}

	/** The usual {@code offset} and {@code count} pair for paginated reads. */
	public JsonSchema pagination(int defaultCount) {
		integer("offset", "How many items to skip, for paging. 0 by default.", 0, Integer.MAX_VALUE);
		integer("count", "How many items to return, 1 to " + MAX_COUNT + ". " + defaultCount + " by default.", 1, MAX_COUNT);
		return this;
	}

	public JsonSchema required(String... names) {
		for (String name : names) {
			if (!properties.has(name))
				throw new IllegalArgumentException("Unknown property " + name);
			required.add(name);
			requiredNames.add(name);
		}
		return this;
	}

	public List<String> propertyNames() {
		return List.copyOf(names);
	}

	public List<String> requiredNames() {
		return List.copyOf(requiredNames);
	}

	public JsonObject build() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties.deepCopy());
		if (!required.isEmpty())
			schema.add("required", required.deepCopy());
		schema.addProperty("additionalProperties", false);
		return schema;
	}
}
