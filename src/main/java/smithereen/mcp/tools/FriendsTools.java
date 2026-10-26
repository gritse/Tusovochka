package smithereen.mcp.tools;

import java.util.List;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.mcp.ToolCallContext;
import smithereen.mcp.ToolResult;
import smithereen.model.apps.ClientAppPermission;

/**
 * Friends, friend requests and friend lists.
 */
public class FriendsTools {
	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_friends")
						.title("Get friends")
						.description("Returns the friends of the current user, or of another user whose friend list is visible, with the total count.")
						.schema(JsonSchema.object()
								.integer("user_id", "Whose friends; the current user when omitted.")
								.integer("list_id", "Only friends in this friend list (see get_friend_lists).")
								.stringEnum("order", "Sort order: name, first_name, last_name, id, random, or for the current user only: hints (closest first), recent (newest friendships first).", "name", "first_name", "last_name", "id", "random", "hints", "recent")
								.pagination(50))
						.scopes(ClientAppPermission.FRIENDS_READ)
						.readOnly()
						.fixed("fields", UsersTools.USER_FIELDS)
						.api("friends.get")
						.build(),
				ApiTool.builder("get_online_friends")
						.title("Get friends who are online")
						.description("Returns the friends of the current user who are online right now.")
						.schema(JsonSchema.object()
								.integer("user_id", "Whose friends; the current user when omitted.")
								.pagination(50))
						.scopes(ClientAppPermission.FRIENDS_READ)
						.readOnly()
						.fixed("fields", UsersTools.USER_FIELDS)
						.api("friends.getOnline")
						.build(),
				ApiTool.builder("get_mutual_friends")
						.title("Get mutual friends")
						.description("Returns the friends the current user and another user have in common.")
						.schema(JsonSchema.object()
								.integer("user_id", "The other user.")
								.pagination(50)
								.required("user_id"))
						.scopes(ClientAppPermission.FRIENDS_READ)
						.readOnly()
						.call((ctx, args) -> {
							var params = ToolCallContext.params(args, List.of("user_id", "offset", "count"));
							params.put("target_user_id", params.remove("user_id"));
							params.put("fields", UsersTools.USER_FIELDS);
							return ctx.result(ctx.api("friends.getMutual", params));
						})
						.build(),
				ApiTool.builder("get_friend_requests")
						.title("Get incoming friend requests")
						.description("Returns the friend requests other users sent to the current user, with the message they attached and mutual friends. Accept one with add_friend, decline one with remove_friend.")
						.schema(JsonSchema.object().pagination(20))
						.scopes(ClientAppPermission.FRIENDS_READ)
						.readOnly()
						.fixed("extended", true)
						.fixed("need_mutual", true)
						.fixed("fields", UsersTools.USER_FIELDS)
						.api("friends.getRequests")
						.build(),
				ApiTool.builder("add_friend")
						.title("Add a friend or accept a request")
						.description("Sends a friend request to a user, or accepts their pending request. Returns what happened: request_sent, request_accepted, or followed (the user has already been added).")
						.schema(JsonSchema.object()
								.integer("user_id", "The user to befriend.")
								.string("message", "An optional message to go with the friend request.")
								.required("user_id"))
						.scopes(ClientAppPermission.FRIENDS_WRITE)
						.idempotent()
						.call((ctx, args) -> {
							var params = ToolCallContext.params(args, List.of("user_id", "message"));
							Object message = params.remove("message");
							if (message != null)
								params.put("text", message);
							return ctx.result(ctx.api("friends.add", params));
						})
						.build(),
				ApiTool.builder("remove_friend")
						.title("Remove a friend or decline a request")
						.description("Removes a user from the current user's friends, cancels a friend request the user sent, or declines a request received from that user. Returns what happened.")
						.schema(JsonSchema.object()
								.integer("user_id", "The user to remove.")
								.required("user_id"))
						.scopes(ClientAppPermission.FRIENDS_WRITE)
						.destructive()
						.idempotent()
						.api("friends.delete")
						.build(),
				ApiTool.builder("get_friend_lists")
						.title("Get friend lists")
						.description("Returns the user's friend lists (close friends, family, and lists they created), with their IDs for use in get_friends.")
						.scopes(ClientAppPermission.FRIENDS_READ)
						.readOnly()
						.api("friends.getLists")
						.build()
		);
	}
}
