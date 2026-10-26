package smithereen.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import smithereen.mcp.tools.FeedTools;
import smithereen.mcp.tools.FriendsTools;
import smithereen.mcp.tools.GroupsTools;
import smithereen.mcp.tools.MessagesTools;
import smithereen.mcp.tools.MiscTools;
import smithereen.mcp.tools.UsersTools;
import smithereen.mcp.tools.WallTools;
import smithereen.model.apps.AppAccessToken;
import smithereen.model.apps.ClientAppPermission;

/**
 * All tools the server offers, and the subset a given token may use.
 */
public class ToolRegistry {
	private final List<McpTool> tools;
	private final Map<String, McpTool> byName = new HashMap<>();

	public ToolRegistry(List<McpTool> tools) {
		this.tools = List.copyOf(tools);
		for (McpTool tool : tools) {
			if (byName.put(tool.name(), tool) != null)
				throw new IllegalArgumentException("Duplicate tool " + tool.name());
		}
	}

	public static ToolRegistry withAllTools() {
		ArrayList<McpTool> all = new ArrayList<>();
		all.addAll(UsersTools.tools());
		all.addAll(FeedTools.tools());
		all.addAll(WallTools.tools());
		all.addAll(FriendsTools.tools());
		all.addAll(GroupsTools.tools());
		all.addAll(MessagesTools.tools());
		all.addAll(MiscTools.tools());
		return new ToolRegistry(all);
	}

	public List<McpTool> all() {
		return tools;
	}

	public McpTool get(String name) {
		return byName.get(name);
	}

	public static boolean hasScopes(AppAccessToken token, McpTool tool) {
		if (token.permissions().contains(ClientAppPermission.PASSWORD_GRANT_USED))
			return true;
		return token.permissions().containsAll(tool.requiredScopes());
	}

	/** The tools this token can call, in registration order. */
	public List<McpTool> toolsFor(AppAccessToken token) {
		ArrayList<McpTool> res = new ArrayList<>();
		for (McpTool tool : tools) {
			if (hasScopes(token, tool))
				res.add(tool);
		}
		return Collections.unmodifiableList(res);
	}
}
