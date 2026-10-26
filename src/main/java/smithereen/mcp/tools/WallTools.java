package smithereen.mcp.tools;

import java.util.List;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.mcp.ToolCallContext;
import smithereen.mcp.ToolResult;
import smithereen.model.apps.ClientAppPermission;

/**
 * Walls, posts, comments and likes.
 */
public class WallTools {
	private static final String OWNER_ID = "The wall owner: a user ID, or a group ID with a minus sign for a group wall, for example -15. The current user's own wall when omitted.";
	private static final String MARKDOWN = "The text, in Markdown. Mention a user with @username.";
	private static final String LIKE_TYPE = "What kind of object: post (a wall post or a comment under a wall post, both have integer IDs), photo, photo_comment (a comment under a photo), or topic_comment (a comment in a board topic).";
	private static final String[] LIKE_TYPES = {"post", "photo", "photo_comment", "topic_comment"};
	private static final String LIKE_ITEM_ID = "The object's ID as a string: the integer ID for post, the string ID for photo, photo_comment and topic_comment.";

	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_wall")
						.title("Get wall posts")
						.description("Returns the posts on a user's or a group's wall, newest first, with like and comment counts. Authors of the posts are in the profiles and groups lists of the result.")
						.schema(JsonSchema.object()
								.integer("owner_id", OWNER_ID)
								.stringEnum("filter", "Which posts: owner (posts by the wall owner, the default), others (posts by other people), or all.", "owner", "others", "all")
								.pagination(20))
						.scopes(ClientAppPermission.WALL_READ)
						.readOnly()
						.fixed("extended", true)
						.api("wall.get")
						.build(),
				ApiTool.builder("get_posts")
						.title("Get posts by ID")
						.description("Returns specific wall posts or comments by their numeric IDs, with their text, attachments and counters.")
						.schema(JsonSchema.object()
								.intList("post_ids", "The post IDs, up to 50.")
								.required("post_ids"))
						.scopes(ClientAppPermission.WALL_READ)
						.readOnly()
						.fixed("extended", true)
						.call((ctx, args) -> {
							var params = ToolCallContext.params(args, List.of("post_ids"));
							params.put("posts", params.remove("post_ids"));
							params.put("extended", true);
							return ctx.result(ctx.api("wall.getById", params));
						})
						.build(),
				ApiTool.builder("get_post_comments")
						.title("Get comments on a post")
						.description("Returns the comments under a wall post, oldest first by default, with like counts and who wrote them. Comments are threaded: a reply names the comment it answers.")
						.schema(JsonSchema.object()
								.integer("post_id", "The ID of the post.")
								.stringEnum("sort", "asc for oldest first (the default), desc for newest first.", "asc", "desc")
								.stringEnum("view_type", "flat (the default), threaded, or two_level.", "flat", "threaded", "two_level")
								.pagination(20)
								.required("post_id"))
						.scopes(ClientAppPermission.WALL_READ)
						.readOnly()
						.fixed("extended", true)
						.fixed("need_likes", true)
						.api("wall.getComments")
						.build(),
				ApiTool.builder("create_post")
						.title("Create a wall post")
						.description("Publishes a new post on the user's own wall, on a friend's wall, or in a group. Returns the ID of the new post. Pass the same guid on a retry to avoid posting twice.")
						.schema(JsonSchema.object()
								.integer("owner_id", OWNER_ID)
								.string("message", MARKDOWN)
								.string("content_warning", "A content warning that hides the post text until the reader expands it.")
								.string("guid", "Any unique string of your choice; a second call with the same guid returns the post created by the first instead of creating another one.")
								.required("message"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.fixed("text_format", "markdown")
						.api("wall.post")
						.build(),
				ApiTool.builder("create_comment")
						.title("Comment on a post")
						.description("Adds a comment under a wall post, or a reply to another comment on that post. Returns the ID of the new comment.")
						.schema(JsonSchema.object()
								.integer("post_id", "The ID of the post to comment on (the top-level post, not a comment).")
								.string("message", MARKDOWN)
								.integer("reply_to_comment", "The ID of the comment this is a reply to, when replying to a comment rather than to the post.")
								.string("guid", "Any unique string of your choice, to make a retry safe.")
								.required("post_id", "message"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.fixed("text_format", "markdown")
						.api("wall.createComment")
						.build(),
				ApiTool.builder("get_post_source")
						.title("Get the Markdown source of a post or comment")
						.description("Returns the original Markdown of a post or comment the user wrote and can still edit, exactly as it was submitted, for use with edit_post. Fails with access_denied for other people's posts or after the edit window has passed.")
						.schema(JsonSchema.object().integer("post_id", "The ID of the post or comment.").required("post_id"))
						.scopes(ClientAppPermission.WALL_READ)
						.readOnly()
						.api("wall.getEditSource")
						.build(),
				ApiTool.builder("edit_post")
						.title("Edit a post or comment")
						.description("Replaces the text of a post or comment the user wrote within the last 24 hours (can_edit in the post tells whether it is still editable). Returns its ID. Use get_post_source first to get the exact Markdown to modify.")
						.schema(JsonSchema.object()
								.integer("post_id", "The ID of the post or comment to edit.")
								.string("message", MARKDOWN)
								.string("content_warning", "A content warning, or omit to keep the current one.")
								.required("post_id", "message"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.idempotent()
						.fixed("text_format", "markdown")
						.api("wall.edit")
						.build(),
				ApiTool.builder("delete_post")
						.title("Delete a post or comment")
						.description("Deletes a post or comment the user wrote, or one on the user's own wall. This can not be undone.")
						.schema(JsonSchema.object()
								.integer("post_id", "The ID of the post or comment to delete.")
								.required("post_id"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.destructive()
						.idempotent()
						.api("wall.delete")
						.build(),
				ApiTool.builder("repost")
						.title("Repost a post")
						.description("Shares an existing post on the user's own wall, optionally with a comment of their own. Returns the ID of the new post.")
						.schema(JsonSchema.object()
								.integer("post_id", "The ID of the post to repost.")
								.string("message", "An optional comment to add above the reposted content, in Markdown.")
								.string("guid", "Any unique string of your choice, to make a retry safe.")
								.required("post_id"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.fixed("text_format", "markdown")
						.api("wall.repost")
						.build(),
				ApiTool.builder("pin_post")
						.title("Pin a post")
						.description("Pins a post to the top of the user's own wall or of a group wall they manage.")
						.schema(JsonSchema.object().integer("post_id", "The ID of the post to pin.").required("post_id"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.idempotent()
						.api("wall.pin")
						.build(),
				ApiTool.builder("unpin_post")
						.title("Unpin a post")
						.description("Removes the pin from a pinned post.")
						.schema(JsonSchema.object().integer("post_id", "The ID of the pinned post.").required("post_id"))
						.scopes(ClientAppPermission.WALL_WRITE)
						.idempotent()
						.api("wall.unpin")
						.build(),
				ApiTool.builder("like")
						.title("Like a post, comment or photo")
						.description("Likes a wall post, a comment or a photo as the user. Returns the new number of likes. Liking something already liked changes nothing.")
						.schema(JsonSchema.object()
								.stringEnum("type", LIKE_TYPE, LIKE_TYPES)
								.string("item_id", LIKE_ITEM_ID)
								.required("type", "item_id"))
						.scopes(ClientAppPermission.LIKES_WRITE)
						.idempotent()
						.api("likes.add")
						.build(),
				ApiTool.builder("unlike")
						.title("Remove a like")
						.description("Removes the user's like from a post, comment or photo. Returns the new number of likes.")
						.schema(JsonSchema.object()
								.stringEnum("type", LIKE_TYPE, LIKE_TYPES)
								.string("item_id", LIKE_ITEM_ID)
								.required("type", "item_id"))
						.scopes(ClientAppPermission.LIKES_WRITE)
						.idempotent()
						.api("likes.delete")
						.build(),
				ApiTool.builder("get_likes")
						.title("Get who liked something")
						.description("Returns the users who liked a post, comment or photo, and how many there are in total.")
						.schema(JsonSchema.object()
								.stringEnum("type", LIKE_TYPE, LIKE_TYPES)
								.string("item_id", LIKE_ITEM_ID)
								.bool("friends_only", "Only the user's friends.")
								.pagination(20)
								.required("type", "item_id"))
						.scopes(ClientAppPermission.LIKES_READ)
						.readOnly()
						.fixed("fields", UsersTools.USER_FIELDS)
						.api("likes.getList")
						.build()
		);
	}
}
