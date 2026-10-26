package smithereen.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.List;


import static org.junit.jupiter.api.Assertions.*;

public class FeedToolsTest {
	@Test
	public void notificationsAreTrimmedTogetherWithTheirSideLists() {
		JsonObject response = JsonParser.parseString("""
				{"items": [
				   {"id": 30, "type": "reply", "user_id": 4, "object": {"type": "wall_comment", "wall_comment_id": 168}, "parent": {"type": "wall_post", "wall_post_id": 157}},
				   {"id": 29, "type": "like", "user_ids": {"count": 2, "items": [5, 6]}, "object": {"type": "photo", "photo_id": "ph1"}},
				   {"id": 28, "type": "comment", "user_id": 9, "object": {"type": "photo_comment", "comment_id": "c9"}, "parent": {"type": "photo", "photo_id": "ph2"}}
				 ],
				 "wall_posts": [{"id": 157, "owner_id": -3, "from_id": 7}, {"id": 136, "owner_id": 1, "from_id": 1}],
				 "wall_comments": [{"id": 168, "from_id": 4, "owner_id": 7, "reply_to_user": 7}, {"id": 140, "from_id": 2, "owner_id": 2}],
				 "photo_comments": [{"id": "c9", "from_id": 9}],
				 "board_comments": [],
				 "photos": [{"id": "ph1", "owner_id": 7, "user_id": 7}, {"id": "ph2", "owner_id": 8, "user_id": 8}, {"id": "ph3", "owner_id": 1, "user_id": 1}],
				 "board_topics": [{"id": "t1", "group_id": 3}],
				 "profiles": [{"id": 1}, {"id": 2}, {"id": 4}, {"id": 5}, {"id": 6}, {"id": 7}, {"id": 8}, {"id": 9}],
				 "groups": [{"id": 3}, {"id": 12}],
				 "last_viewed": 20}""").getAsJsonObject();
		FeedTools.trimNotifications(response, 2);
		JsonArray items = response.getAsJsonArray("items");
		assertEquals(2, items.size());
		assertEquals(List.of("157"), ids(response, "wall_posts"));
		assertEquals(List.of("168"), ids(response, "wall_comments"));
		assertEquals(List.of(), ids(response, "photo_comments"));
		assertEquals(List.of("ph1"), ids(response, "photos"));
		assertEquals(List.of(), ids(response, "board_topics"));
		// Actors of the kept items (4, 5, 6) and of the kept objects (7) stay; 1, 2, 8, 9 go
		assertEquals(List.of("4", "5", "6", "7"), ids(response, "profiles"));
		assertEquals(List.of("3"), ids(response, "groups"));
		assertEquals(20, response.get("last_viewed").getAsInt());
	}

	private static List<String> ids(JsonObject response, String key) {
		return response.getAsJsonArray(key).asList().stream().map(e -> e.getAsJsonObject().get("id").getAsString()).toList();
	}
}
