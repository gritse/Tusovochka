package smithereen.model;

import org.jetbrains.annotations.Nullable;

import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupFeatureState;
import smithereen.model.objects.Actor;

/**
 * Decides which posts and comments can be shared to someone's wall. A repost shows the original to everyone who can
 * see the reposter's wall, so only content meant for everyone qualifies:
 * <ul>
 *     <li>the post (and, for a comment, the post it belongs to) is public;</li>
 *     <li>on a group wall, the group is open, not banned, and its wall is not disabled;</li>
 *     <li>a post someone wrote on another user's wall (and comments on it) only when that user lets everyone see
 *     others' posts on their wall.</li>
 * </ul>
 * This only looks at the content, not at who reposts it: blocks are checked by {@code WallController}, and whether
 * each viewer may see a repost's original is checked again when the repost is displayed.
 */
public final class RepostPolicy{
	private RepostPolicy() {}

	/**
	 * @param post     The post or comment to repost.
	 * @param topLevel For a comment, the post it belongs to; ignored for a top-level post.
	 * @param owner    The owner of the wall the post is on: a {@link User} or a {@link Group}.
	 */
	public static boolean canBeReposted(@Nullable Post post, @Nullable Post topLevel, @Nullable Actor owner) {
		if (post == null || post.isDeleted() || owner == null || post.privacy != Post.Privacy.PUBLIC)
			return false;
		Post wallPost = post.getReplyLevel() > 0 ? topLevel : post;
		if (wallPost == null || wallPost.isDeleted() || wallPost.privacy != Post.Privacy.PUBLIC)
			return false;
		// A comment is judged by its own thread's post, so refuse a mismatched one instead of trusting the caller
		if (wallPost != post && (wallPost.id != post.replyKey.getFirst() || wallPost.ownerID != post.ownerID))
			return false;
		if (owner.getOwnerID() != wallPost.ownerID)
			return false;
		return switch (owner) {
			case Group group -> group.accessType == Group.AccessType.OPEN && group.banStatus == GroupBanStatus.NONE
					&& group.wallState != GroupFeatureState.DISABLED;
			case User user -> wallPost.ownerID == wallPost.authorID
					|| user.getPrivacySetting(UserPrivacySettingKey.WALL_OTHERS_POSTS).baseRule == PrivacySetting.Rule.EVERYONE;
			default -> false;
		};
	}
}
