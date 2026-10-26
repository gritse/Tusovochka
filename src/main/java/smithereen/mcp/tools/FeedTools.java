package smithereen.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.mcp.ToolCallContext;
import smithereen.model.apps.ClientAppPermission;

/**
 * The news feeds and notifications: what happened since the user last looked.
 */
public class FeedTools {
	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_newsfeed")
						.title("Get the news feed")
						.description("Returns the user's news feed: new posts by friends, photos, new friendships, group and event activity, newest first. Each item has a type and the object it is about; authors are in the profiles and groups lists. To read the next page, pass the returned next_from as start_from.")
						.schema(JsonSchema.object()
								.stringList("filters", "Only these item types: post, photo, photo_tag, friend, group, event, board, relation. Everything by default.")
								.string("start_from", "The next_from value from the previous page, for paging.")
								.integer("count", "How many items to return, 1 to " + JsonSchema.MAX_COUNT + ". 25 by default.", 1, JsonSchema.MAX_COUNT))
						.scopes(ClientAppPermission.NEWSFEED)
						.readOnly()
						.fixed("extended", true)
						.api("newsfeed.get")
						.build(),
				ApiTool.builder("get_groups_feed")
						.title("Get the groups feed")
						.description("Returns the feed of the groups and events the user is a member of: posts, photos and board topics, newest first. To read the next page, pass the returned next_from as start_from.")
						.schema(JsonSchema.object()
								.stringList("filters", "Only these item types: post, photo, board. Everything by default.")
								.string("start_from", "The next_from value from the previous page, for paging.")
								.integer("count", "How many items to return, 1 to " + JsonSchema.MAX_COUNT + ". 25 by default.", 1, JsonSchema.MAX_COUNT))
						.scopes(ClientAppPermission.NEWSFEED)
						.readOnly()
						.fixed("extended", true)
						.api("newsfeed.getGroups")
						.build(),
				ApiTool.builder("get_comments_feed")
						.title("Get the comments feed")
						.description("Returns posts, photos and board topics the user commented on or follows that have new comments, newest first, each with its 3 latest comments including replies to replies. This is where answers to the user's comments show up; use get_post_comments for the whole thread.")
						.schema(JsonSchema.object()
								.stringList("filters", "Only comments on these object types: post, photo, board. Everything by default.")
								.pagination(25))
						.scopes(ClientAppPermission.NEWSFEED)
						.readOnly()
						.fixed("extended", true)
						.fixed("last_comments", 3)
						.fixed("comment_view_type", "flat")
						.api("newsfeed.getComments")
						.build(),
				ApiTool.builder("get_notifications")
						.title("Get notifications")
						.description("Returns the user's notifications, newest first: likes, reposts, comments and replies on their content, mentions, accepted friend requests, group invitations and so on. Similar notifications are grouped. Use max_id to page back from a known notification.")
						.schema(JsonSchema.object()
								.integer("max_id", "Only notifications with an ID up to this one, for paging.")
								.pagination(20))
						.scopes(ClientAppPermission.NOTIFICATIONS)
						.readOnly()
						.call((ctx, args) -> {
							var params = ToolCallContext.params(args, List.of("max_id", "offset", "count"));
							JsonElement result = ctx.api("notifications.get", params);
							// The API groups similar notifications after paging, so it can return more than asked for
							int count = params.get("count") instanceof Integer c ? c : 20;
							if (result instanceof JsonObject obj && obj.get("items") instanceof JsonArray items && items.size() > count) {
								JsonArray trimmed = new JsonArray();
								for (int i = 0; i < count; i++)
									trimmed.add(items.get(i));
								obj.add("items", trimmed);
							}
							return ctx.result(result);
						})
						.build(),
				ApiTool.builder("mark_notifications_viewed")
						.title("Mark notifications as viewed")
						.description("Marks all of the user's notifications as seen, so the unread counter goes back to zero. Returns true if anything changed.")
						.scopes(ClientAppPermission.NOTIFICATIONS)
						.idempotent()
						.api("notifications.markAsViewed")
						.build()
		);
	}
}
