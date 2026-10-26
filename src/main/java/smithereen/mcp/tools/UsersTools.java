package smithereen.mcp.tools;

import com.google.gson.JsonElement;
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
						.description("Returns the profile of the user this access token belongs to, together with what is pending for them: unread notifications, incoming friend requests, group and event invitations, new photo tags. Call this first to learn whose account the tools act on.")
						.readOnly()
						.call((ctx, args) -> {
							Map<String, Object> params = ToolCallContext.params();
							params.put("fields", USER_FIELDS);
							JsonObject result = new JsonObject();
							result.add("user", ctx.api("users.get", params).getAsJsonArray().get(0));
							result.add("pending", renameCounters(ctx.api("account.getCounters")));
							return ctx.result(result);
						})
						.build(),
				ApiTool.builder("get_users")
						.title("Get users by ID or username")
						.description("Returns profiles of users given their numeric IDs or usernames (screen names), up to 50 at once. IDs and names that match nobody are left out of the result without an error.")
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
						.title("Get pending counters")
						.description("Returns what is waiting for the user: the number of unread notifications, pending incoming friend requests, pending group and event invitations, and new photo tags to confirm. These are not totals (use get_friends or get_my_groups for those), and unread private messages are not counted; see get_messages.")
						.readOnly()
						.call((ctx, args) -> ctx.result(renameCounters(ctx.api("account.getCounters"))))
						.build()
		);
	}

	/** The API names the counters like totals (friends, groups); they are counts of pending things. */
	static JsonObject renameCounters(JsonElement counters) {
		JsonObject in = counters.getAsJsonObject();
		JsonObject out = new JsonObject();
		out.add("unread_notifications", in.get("notifications"));
		out.add("friend_requests", in.get("friends"));
		out.add("group_invites", in.get("groups"));
		out.add("event_invites", in.get("events"));
		out.add("new_photo_tags", in.get("photos"));
		return out;
	}
}
