package smithereen.model.notifications;

import java.util.EnumSet;

import smithereen.model.feed.FriendsNewsfeedTypeFilter;
import smithereen.model.feed.NewsfeedEntry;
import smithereen.util.TranslatableEnum;

/**
 * What a user gets browser push notifications about. The first group mirrors the site's own notifications
 * ({@link RealtimeNotificationSettingType}), the others mirror the three tabs of the news feed, so that a user of a
 * small network can be told about everything their friends and groups do, and turn off whatever gets annoying.
 */
public enum PushNotificationType implements TranslatableEnum<PushNotificationType> {
	MAIL,
	FRIEND_REQUESTS,
	LIKES,
	REPOSTS,
	REPLIES,
	WALL,
	MENTIONS,
	PHOTO_TAGS,
	GROUP_INVITES,

	FRIENDS_POSTS,
	FRIENDS_PHOTOS,
	FRIENDS_TOPICS,
	FRIENDS_FRIENDS,
	FRIENDS_GROUPS,
	FRIENDS_EVENTS,
	FRIENDS_PHOTO_TAGS,
	FRIENDS_PERSONAL_INFO,

	GROUPS_POSTS,
	GROUPS_TOPICS,
	GROUPS_PHOTOS,

	COMMENTS;

	public enum Group {
		NOTIFICATIONS,
		FRIENDS_FEED,
		GROUPS_FEED,
		COMMENTS_FEED
	}

	/**
	 * What a user gets until they change the settings: everything the site itself notifies about, new content from
	 * friends and groups, and comment threads. Friends' changes to their friend lists, groups and profiles are off.
	 */
	public static EnumSet<PushNotificationType> defaultTypes() {
		return EnumSet.of(MAIL, FRIEND_REQUESTS, LIKES, REPOSTS, REPLIES, WALL, MENTIONS, PHOTO_TAGS, GROUP_INVITES,
				FRIENDS_POSTS, FRIENDS_PHOTOS, FRIENDS_TOPICS, GROUPS_POSTS, GROUPS_TOPICS, GROUPS_PHOTOS, COMMENTS);
	}

	public static PushNotificationType forRealtimeType(RealtimeNotificationSettingType type) {
		return switch (type) {
			case MAIL -> MAIL;
			case FRIEND_REQUESTS -> FRIEND_REQUESTS;
			case LIKES -> LIKES;
			case REPOSTS -> REPOSTS;
			case REPLIES -> REPLIES;
			case WALL -> WALL;
			case MENTIONS -> MENTIONS;
			case PHOTO_TAGS -> PHOTO_TAGS;
			case GROUP_INVITES -> GROUP_INVITES;
		};
	}

	/**
	 * The setting that covers an entry of the friends feed, or null for entries that are never pushed.
	 */
	public static PushNotificationType forFriendsFeedEntry(NewsfeedEntry.Type type) {
		return switch (type) {
			case POST -> FRIENDS_POSTS;
			case ADD_PHOTO -> FRIENDS_PHOTOS;
			case BOARD_TOPIC -> FRIENDS_TOPICS;
			case ADD_FRIEND -> FRIENDS_FRIENDS;
			case JOIN_GROUP, CREATE_GROUP -> FRIENDS_GROUPS;
			case JOIN_EVENT, CREATE_EVENT -> FRIENDS_EVENTS;
			case PHOTO_TAG -> FRIENDS_PHOTO_TAGS;
			case RELATIONSHIP_STATUS -> FRIENDS_PERSONAL_INFO;
			case RETOOT, GROUPED, PHOTO -> null;
		};
	}

	public static PushNotificationType forGroupsFeedEntry(NewsfeedEntry.Type type) {
		return switch (type) {
			case POST -> GROUPS_POSTS;
			case BOARD_TOPIC -> GROUPS_TOPICS;
			case ADD_PHOTO -> GROUPS_PHOTOS;
			default -> null;
		};
	}

	/**
	 * The friends feed filter this setting corresponds to, used to honor a friend's own "what to show in the news"
	 * setting ({@link smithereen.model.User#newsTypesToShow}).
	 */
	public FriendsNewsfeedTypeFilter getFriendsFeedFilter() {
		return switch (this) {
			case FRIENDS_POSTS -> FriendsNewsfeedTypeFilter.POSTS;
			case FRIENDS_PHOTOS -> FriendsNewsfeedTypeFilter.PHOTOS;
			case FRIENDS_TOPICS -> FriendsNewsfeedTypeFilter.TOPICS;
			case FRIENDS_FRIENDS -> FriendsNewsfeedTypeFilter.FRIENDS;
			case FRIENDS_GROUPS -> FriendsNewsfeedTypeFilter.GROUPS;
			case FRIENDS_EVENTS -> FriendsNewsfeedTypeFilter.EVENTS;
			case FRIENDS_PHOTO_TAGS -> FriendsNewsfeedTypeFilter.PHOTO_TAGS;
			case FRIENDS_PERSONAL_INFO -> FriendsNewsfeedTypeFilter.PERSONAL_INFO;
			default -> null;
		};
	}

	public Group getGroup() {
		return switch (this) {
			case MAIL, FRIEND_REQUESTS, LIKES, REPOSTS, REPLIES, WALL, MENTIONS, PHOTO_TAGS, GROUP_INVITES -> Group.NOTIFICATIONS;
			case FRIENDS_POSTS, FRIENDS_PHOTOS, FRIENDS_TOPICS, FRIENDS_FRIENDS, FRIENDS_GROUPS, FRIENDS_EVENTS, FRIENDS_PHOTO_TAGS, FRIENDS_PERSONAL_INFO -> Group.FRIENDS_FEED;
			case GROUPS_POSTS, GROUPS_TOPICS, GROUPS_PHOTOS -> Group.GROUPS_FEED;
			case COMMENTS -> Group.COMMENTS_FEED;
		};
	}

	@Override
	public String getLangKey() {
		return switch (this) {
			case MAIL, FRIEND_REQUESTS, LIKES, REPOSTS, REPLIES, WALL, MENTIONS, PHOTO_TAGS, GROUP_INVITES -> "settings_notifications_" + toString().toLowerCase();
			case FRIENDS_POSTS, GROUPS_POSTS -> "feed_type_posts";
			case FRIENDS_PHOTOS, GROUPS_PHOTOS -> "feed_type_photos";
			case FRIENDS_TOPICS, GROUPS_TOPICS -> "feed_type_board_topics";
			case FRIENDS_FRIENDS -> "feed_type_friends";
			case FRIENDS_GROUPS -> "feed_type_groups";
			case FRIENDS_EVENTS -> "feed_type_events";
			case FRIENDS_PHOTO_TAGS -> "feed_type_tags";
			case FRIENDS_PERSONAL_INFO -> "feed_type_personal";
			case COMMENTS -> "settings_push_comments_feed";
		};
	}
}
