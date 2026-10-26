package smithereen.mcp.tools;

import java.util.List;

import smithereen.mcp.ApiTool;
import smithereen.mcp.JsonSchema;
import smithereen.mcp.McpTool;
import smithereen.model.apps.ClientAppPermission;

/**
 * Groups and events (an event is a group with a date and a place).
 */
public class GroupsTools {
	private static final String GROUP_FIELDS = "screen_name,status,is_member,is_admin";

	public static List<McpTool> tools() {
		return List.of(
				ApiTool.builder("get_my_groups")
						.title("Get the user's groups and events")
						.description("Returns the groups the current user is a member of, or their events, or only the groups and events they manage. To post in a group, use create_post with owner_id set to the negative group ID.")
						.schema(JsonSchema.object()
								.stringList("filter", "Narrow the list: events (events instead of groups), admin (only those the user administers), moder (only those the user moderates or administers). Combine events with admin or moder for managed events.")
								.pagination(50))
						.scopes(ClientAppPermission.GROUPS_READ)
						.readOnly()
						.fixed("fields", GROUP_FIELDS)
						.api("groups.get")
						.build(),
				ApiTool.builder("get_groups")
						.title("Get groups by ID or username")
						.description("Returns groups or events by their numeric IDs or usernames, with description, member count and, for events, start and end dates and the place.")
						.schema(JsonSchema.object()
								.stringList("group_ids", "Numeric group IDs (positive) or usernames, up to 50.")
								.required("group_ids"))
						.readOnly()
						.fixed("fields", GROUP_FIELDS + ",description,start_date,finish_date,place,members_count")
						.api("groups.getById")
						.build(),
				ApiTool.builder("search_groups")
						.title("Search groups or events")
						.description("Finds groups or events on this server by name.")
						.schema(JsonSchema.object()
								.string("q", "The search query.")
								.stringEnum("type", "groups (the default) or events.", "groups", "events")
								.integer("count", "How many results, 1 to " + JsonSchema.MAX_COUNT + ". 20 by default.", 1, JsonSchema.MAX_COUNT)
								.required("q"))
						.readOnly()
						.fixed("fields", GROUP_FIELDS)
						.api("groups.search")
						.build(),
				ApiTool.builder("get_group_members")
						.title("Get group members")
						.description("Returns the members of a group or the participants of an event, or only its managers.")
						.schema(JsonSchema.object()
								.integer("group_id", "The group or event ID (positive).")
								.stringEnum("filter", "managers to list only the administrators and moderators with their roles.", "managers")
								.stringEnum("sort", "id_asc (the default), id_desc, time_asc, time_desc (by join time), random.", "id_asc", "id_desc", "time_asc", "time_desc", "random")
								.pagination(50)
								.required("group_id"))
						.scopes(ClientAppPermission.GROUPS_READ)
						.readOnly()
						.fixed("fields", UsersTools.USER_FIELDS)
						.api("groups.getMembers")
						.build(),
				ApiTool.builder("join_group")
						.title("Join a group or event")
						.description("Joins a group, or marks the user as going to an event (or as maybe going). For a closed group this sends a join request; for an invitation it accepts it.")
						.schema(JsonSchema.object()
								.integer("group_id", "The group or event ID (positive).")
								.bool("not_sure", "For events: true to answer \"maybe\" instead of \"going\".")
								.required("group_id"))
						.scopes(ClientAppPermission.GROUPS_WRITE)
						.idempotent()
						.api("groups.join")
						.build(),
				ApiTool.builder("leave_group")
						.title("Leave a group or event")
						.description("Leaves a group, withdraws from an event, cancels a pending join request, or declines an invitation.")
						.schema(JsonSchema.object()
								.integer("group_id", "The group or event ID (positive).")
								.required("group_id"))
						.scopes(ClientAppPermission.GROUPS_WRITE)
						.destructive()
						.idempotent()
						.api("groups.leave")
						.build(),
				ApiTool.builder("get_group_invites")
						.title("Get group and event invitations")
						.description("Returns the groups or events the user has been invited to, and who invited them. Accept with join_group, decline with leave_group.")
						.schema(JsonSchema.object()
								.stringEnum("type", "groups (the default) or events.", "groups", "events")
								.pagination(20))
						.scopes(ClientAppPermission.GROUPS_READ)
						.readOnly()
						.fixed("extended", true)
						.fixed("fields", GROUP_FIELDS)
						.api("groups.getInvites")
						.build(),
				ApiTool.builder("invite_to_group")
						.title("Invite a friend to a group or event")
						.description("Invites one of the user's friends to a group or event the user is a member of.")
						.schema(JsonSchema.object()
								.integer("group_id", "The group or event ID (positive).")
								.integer("user_id", "The friend to invite.")
								.required("group_id", "user_id"))
						.scopes(ClientAppPermission.GROUPS_WRITE)
						.idempotent()
						.api("groups.invite")
						.build(),
				ApiTool.builder("create_group")
						.title("Create a group or event")
						.description("Creates a new group, or a new event with a start date. The user becomes its owner. Returns the ID of the new group.")
						.schema(JsonSchema.object()
								.string("name", "The name.")
								.stringEnum("type", "group or event.", "group", "event")
								.string("description", "A description, plain text.")
								.integer("start_date", "For events: when it starts, as a Unix timestamp in seconds. Must be in the future.")
								.required("name", "type"))
						.scopes(ClientAppPermission.GROUPS_WRITE)
						.api("groups.create")
						.build(),
				ApiTool.builder("edit_group")
						.title("Edit a group or event")
						.description("Changes the name, description, website, or for events the dates and the place, of a group the user manages. Only the given fields change.")
						.schema(JsonSchema.object()
								.integer("group_id", "The group or event ID (positive).")
								.string("name", "The new name.")
								.string("description", "The new description.")
								.string("site", "The website URL.")
								.integer("start_date", "For events: the new start, as a Unix timestamp in seconds.")
								.integer("finish_date", "For events: the new end, as a Unix timestamp in seconds, or 0 to remove it.")
								.string("place", "For events: where it takes place.")
								.required("group_id"))
						.scopes(ClientAppPermission.GROUPS_WRITE)
						.idempotent()
						.api("groups.edit")
						.build()
		);
	}
}
