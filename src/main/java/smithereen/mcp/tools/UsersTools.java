package smithereen.mcp.tools;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.mcp.ToolCallContext;
import smithereen.mcp.ToolResult;

/**
 * Who the agent is, and looking people up.
 */
public class UsersTools {
	/** The user fields the API adds on request; the model always wants the username. */
	static final String USER_FIELDS = "screen_name,status";

	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_me")
						.title("Get the current user")
						.description("Returns the profile of the user this access token belongs to, together with their unread counters (friend requests, notifications, messages). Call this first to learn whose account the tools act on.")
						.readOnly()
						.call((ctx, args) -> {
							Map<String, Object> params = ToolCallContext.params();
							params.put("fields", USER_FIELDS);
							JsonObject result = new JsonObject();
							result.add("user", ctx.api("users.get", params).getAsJsonArray().get(0));
							result.add("counters", ctx.api("account.getCounters"));
							return ToolResult.ofApi(result);
						})
						.build(),
				ApiTool.builder("get_users")
						.title("Get users by ID or username")
						.description("Returns profiles of users given their numeric IDs or usernames (screen names), up to 50 at once.")
						.schema(JsonSchema.object()
								.stringList("user_ids", "Numeric user IDs or usernames, for example [\"12\", \"alice\"].")
								.required("user_ids"))
						.readOnly()
						.fixed("fields", USER_FIELDS)
						.api("users.get")
						.build(),
				ApiTool.builder("search_users")
						.title("Search users by name")
						.description("Finds users on this server by name or username. Returns profiles and the total number of matches.")
						.schema(JsonSchema.object()
								.string("q", "The search query: a name, part of a name, or a username.")
								.integer("count", "How many results to return, 1 to " + JsonSchema.MAX_COUNT + ". 20 by default.", 1, JsonSchema.MAX_COUNT)
								.required("q"))
						.readOnly()
						.fixed("fields", USER_FIELDS)
						.api("users.search")
						.build(),
				ApiTool.builder("get_counters")
						.title("Get unread counters")
						.description("Returns how many unread notifications, private messages, friend requests, and group and event invitations the user has.")
						.readOnly()
						.api("account.getCounters")
						.build()
		);
	}
}
