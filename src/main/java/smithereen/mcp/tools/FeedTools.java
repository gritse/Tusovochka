package smithereen.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
							if (result instanceof JsonObject obj)
								trimNotifications(obj, count);
							return ctx.result(result);
						})
						.build(),
				NOTIFICATIONS_VIEWED
		);
	}

	private static final McpTool NOTIFICATIONS_VIEWED = ApiTool.builder("mark_notifications_viewed")
						.title("Mark notifications as viewed")
						.description("Marks all of the user's notifications as seen, so the unread counter goes back to zero. Returns true if anything changed.")
						.scopes(ClientAppPermission.NOTIFICATIONS)
						.idempotent()
						.api("notifications.markAsViewed")
						.build();

	/** The side lists of a notifications response and the keys in items that reference them. */
	private static final Map<String, List<String>> SIDE_LIST_KEYS = Map.of(
			"wall_posts", List.of("wall_post_id"),
			"wall_comments", List.of("wall_comment_id"),
			"photo_comments", List.of("comment_id", "photo_comment_id"),
			"board_comments", List.of("comment_id", "board_comment_id"),
			"photos", List.of("photo_id"),
			"board_topics", List.of("board_topic_id")
	);

	/**
	 * Keeps the first {@code count} notifications and only the posts, comments, photos, topics, profiles and groups
	 * those notifications and their objects refer to.
	 */
	static void trimNotifications(JsonObject response, int count) {
		if (!(response.get("items") instanceof JsonArray items))
			return;
		if (items.size() > count) {
			JsonArray trimmed = new JsonArray();
			for (int i = 0; i < count; i++)
				trimmed.add(items.get(i));
			items = trimmed;
			response.add("items", items);
		}
		Map<String, Set<String>> referenced = new HashMap<>();
		Set<Integer> users = new HashSet<>(), groups = new HashSet<>();
		for (JsonElement item : items) {
			collectReferences(item, referenced, users, groups);
		}
		for (Map.Entry<String, List<String>> e : SIDE_LIST_KEYS.entrySet()) {
			Set<String> keep = new HashSet<>();
			for (String key : e.getValue())
				keep.addAll(referenced.getOrDefault(key, Set.of()));
			if (!(response.get(e.getKey()) instanceof JsonArray list))
				continue;
			JsonArray kept = new JsonArray();
			for (JsonElement obj : list) {
				if (obj instanceof JsonObject o && o.get("id") instanceof JsonPrimitive id && keep.contains(id.getAsString())) {
					kept.add(o);
					collectActors(o, users, groups);
				}
			}
			response.add(e.getKey(), kept);
		}
		filterByID(response, "profiles", users);
		filterByID(response, "groups", groups);
	}

	private static void collectReferences(JsonElement element, Map<String, Set<String>> referenced, Set<Integer> users, Set<Integer> groups) {
		if (!(element instanceof JsonObject obj))
			return;
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			String key = e.getKey();
			JsonElement value = e.getValue();
			if (value instanceof JsonPrimitive p) {
				if (key.endsWith("_id") && !key.equals("user_id"))
					referenced.computeIfAbsent(key, k -> new HashSet<>()).add(p.getAsString());
				else if (key.equals("user_id") && p.isNumber())
					users.add(p.getAsInt());
			} else if (key.equals("user_ids") && value instanceof JsonObject ids && ids.get("items") instanceof JsonArray arr) {
				for (JsonElement id : arr)
					users.add(id.getAsInt());
			} else {
				collectReferences(value, referenced, users, groups);
			}
		}
	}

	/** The users and groups a post, comment, photo or topic refers to. */
	private static void collectActors(JsonObject obj, Set<Integer> users, Set<Integer> groups) {
		for (String key : List.of("from_id", "owner_id", "user_id", "author_id", "reply_to_user", "placer_id")) {
			if (obj.get(key) instanceof JsonPrimitive p && p.isNumber()) {
				int id = p.getAsInt();
				if (id > 0)
					users.add(id);
				else if (id < 0)
					groups.add(-id);
			}
		}
		if (obj.get("group_id") instanceof JsonPrimitive p && p.isNumber())
			groups.add(p.getAsInt());
	}

	private static void filterByID(JsonObject response, String key, Set<Integer> ids) {
		if (!(response.get(key) instanceof JsonArray list))
			return;
		JsonArray kept = new JsonArray();
		for (JsonElement obj : list) {
			if (obj instanceof JsonObject o && o.get("id") instanceof JsonPrimitive id && id.isNumber() && ids.contains(id.getAsInt()))
				kept.add(o);
		}
		response.add(key, kept);
	}
}
