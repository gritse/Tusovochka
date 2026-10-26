package smithereen.mcp.tools;

import java.util.List;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.mcp.ToolCallContext;
import smithereen.mcp.ToolResult;
import smithereen.model.apps.ClientAppPermission;

/**
 * Private messages. Message IDs are strings.
 */
public class MessagesTools {
	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_messages")
						.title("Get private messages")
						.description("Returns the user's received (inbox) or sent (outbox) private messages, newest first, with whether each is read, and the profiles of the other people involved.")
						.schema(JsonSchema.object()
								.stringEnum("folder", "inbox (the default) or outbox.", "inbox", "outbox")
								.pagination(20))
						.scopes(ClientAppPermission.MESSAGES_READ)
						.readOnly()
						.call((ctx, args) -> {
							var params = ToolCallContext.params(args, List.of("offset", "count"));
							params.put("out", args.has("folder") && "outbox".equals(args.get("folder").getAsString()));
							params.put("extended", true);
							return ToolResult.ofApi(ctx.api("messages.get", params));
						})
						.build(),
				ApiTool.builder("get_message_history")
						.title("Get the conversation with a user")
						.description("Returns the private messages exchanged between the current user and another user, newest first.")
						.schema(JsonSchema.object()
								.integer("user_id", "The other user.")
								.pagination(20)
								.required("user_id"))
						.scopes(ClientAppPermission.MESSAGES_READ)
						.readOnly()
						.api("messages.getHistory")
						.build(),
				ApiTool.builder("get_messages_by_id")
						.title("Get private messages by ID")
						.description("Returns specific private messages by their string IDs, with the profiles of the people involved.")
						.schema(JsonSchema.object()
								.stringList("message_ids", "The message IDs, up to 50.")
								.required("message_ids"))
						.scopes(ClientAppPermission.MESSAGES_READ)
						.readOnly()
						.fixed("extended", true)
						.api("messages.getById")
						.build(),
				ApiTool.builder("send_message")
						.title("Send a private message")
						.description("Sends a private message to one or more users, optionally as a reply to a message. Returns the ID of the sent message. Pass the same guid on a retry to avoid sending twice.")
						.schema(JsonSchema.object()
								.intList("to_user_ids", "The recipients' user IDs. Not needed when reply_to is given: the reply goes to the people in that conversation.")
								.string("text", "The message text, in Markdown.")
								.string("subject", "An optional subject line.")
								.string("reply_to", "The ID of the message this replies to.")
								.string("guid", "Any unique string of your choice, to make a retry safe.")
								.required("text"))
						.scopes(ClientAppPermission.MESSAGES_WRITE)
						.call((ctx, args) -> {
							var params = ToolCallContext.params(args, List.of("to_user_ids", "text", "subject", "reply_to", "guid"));
							Object to = params.remove("to_user_ids");
							if (to != null)
								params.put("to", to);
							params.put("body", params.remove("text"));
							params.put("text_format", "markdown");
							return ToolResult.ofApi(ctx.api("messages.send", params));
						})
						.build(),
				ApiTool.builder("mark_message_read")
						.title("Mark a message as read")
						.description("Marks a received private message as read.")
						.schema(JsonSchema.object()
								.string("message_id", "The ID of the received message.")
								.required("message_id"))
						.scopes(ClientAppPermission.MESSAGES_WRITE)
						.idempotent()
						.api("messages.markAsRead")
						.build(),
				ApiTool.builder("delete_message")
						.title("Delete a private message")
						.description("Deletes a private message for the current user. With revoke, a message the user sent is also removed for its recipients.")
						.schema(JsonSchema.object()
								.string("message_id", "The ID of the message.")
								.bool("revoke", "Also delete it for the recipients (only for messages the user sent).")
								.required("message_id"))
						.scopes(ClientAppPermission.MESSAGES_WRITE)
						.destructive()
						.idempotent()
						.api("messages.delete")
						.build(),
				ApiTool.builder("restore_message")
						.title("Restore a deleted message")
						.description("Restores a private message the user deleted for themselves.")
						.schema(JsonSchema.object()
								.string("message_id", "The ID of the deleted message.")
								.required("message_id"))
						.scopes(ClientAppPermission.MESSAGES_WRITE)
						.idempotent()
						.api("messages.restore")
						.build()
		);
	}
}
