package smithereen.mcp;

import com.google.gson.JsonObject;

import java.util.EnumSet;

import smithereen.model.apps.ClientAppPermission;

/**
 * One tool the MCP server offers. Implementations are thin adapters over the JSON API; see {@link ApiTool}.
 */
public interface McpTool {
	String name();

	String title();

	String description();

	JsonSchema schema();

	/** The API scopes the token needs. A token without them does not see the tool in {@code tools/list}. */
	EnumSet<ClientAppPermission> requiredScopes();

	/** True when the tool changes nothing; the client uses this to decide whether to ask the user. */
	boolean readOnly();

	/** True when the tool deletes or removes something that can not be undone from the agent. */
	boolean destructive();

	/** True when repeating the call with the same arguments has no further effect. */
	boolean idempotent();

	ToolResult call(ToolCallContext ctx, JsonObject args);

	/** The {@code tools/list} entry. */
	default JsonObject toJson() {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name());
		tool.addProperty("title", title());
		tool.addProperty("description", description());
		tool.add("inputSchema", schema().build());
		JsonObject annotations = new JsonObject();
		annotations.addProperty("title", title());
		annotations.addProperty("readOnlyHint", readOnly());
		annotations.addProperty("destructiveHint", destructive());
		annotations.addProperty("idempotentHint", idempotent());
		annotations.addProperty("openWorldHint", false);
		tool.add("annotations", annotations);
		return tool;
	}
}
