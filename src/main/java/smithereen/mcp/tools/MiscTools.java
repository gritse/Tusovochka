package smithereen.mcp.tools;

import com.google.gson.JsonObject;

import java.util.List;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.mcp.ToolResult;
import smithereen.model.apps.ClientAppPermission;

/**
 * The server itself, statuses, blocking, and resolving names.
 */
public class MiscTools {
	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_server_info")
						.title("Get information about this server")
						.description("Returns the name, description and rules of this server, and its current announcements to members.")
						.readOnly()
						.call((ctx, args) -> {
							JsonObject result = new JsonObject();
							result.add("server", ctx.api("server.getInfo"));
							result.add("announcements", ctx.api("server.getAnnouncements"));
							return ToolResult.ofApi(result);
						})
						.build(),
				ApiTool.builder("resolve_screen_name")
						.title("Resolve a username")
						.description("Tells whether a username belongs to a user, a group, or nothing, and returns its numeric ID.")
						.schema(JsonSchema.object()
								.string("screen_name", "The username, without @.")
								.required("screen_name"))
						.readOnly()
						.api("utils.resolveScreenName")
						.build(),
				ApiTool.builder("get_status")
						.title("Get a status")
						.description("Returns the short text status of a user or a group.")
						.schema(JsonSchema.object()
								.integer("owner_id", "A user ID, or a negative group ID. The current user when omitted."))
						.readOnly()
						.api("status.get")
						.build(),
				ApiTool.builder("set_status")
						.title("Set the user's status")
						.description("Sets the short text status shown under the user's name on their profile, or clears it when the text is empty. Managers can set a group's status with group_id.")
						.schema(JsonSchema.object()
								.string("text", "The new status text, or an empty string to clear it.")
								.integer("group_id", "Set the status of this group (positive ID) instead of the user's own.")
								.required("text"))
						.scopes(ClientAppPermission.ACCOUNT_WRITE)
						.idempotent()
						.api("status.set")
						.build(),
				ApiTool.builder("get_blocked_users")
						.title("Get blocked users")
						.description("Returns the users the current user has blocked.")
						.schema(JsonSchema.object().pagination(50))
						.scopes(ClientAppPermission.ACCOUNT_WRITE)
						.readOnly()
						.api("account.getBannedUsers")
						.build(),
				ApiTool.builder("block_user")
						.title("Block a user")
						.description("Blocks a user: they can no longer see the current user's content, message them, or add them as a friend.")
						.schema(JsonSchema.object().integer("user_id", "The user to block.").required("user_id"))
						.scopes(ClientAppPermission.ACCOUNT_WRITE)
						.idempotent()
						.api("account.banUser")
						.build(),
				ApiTool.builder("unblock_user")
						.title("Unblock a user")
						.description("Removes a user from the current user's block list.")
						.schema(JsonSchema.object().integer("user_id", "The user to unblock.").required("user_id"))
						.scopes(ClientAppPermission.ACCOUNT_WRITE)
						.idempotent()
						.api("account.unbanUser")
						.build()
		);
	}
}
