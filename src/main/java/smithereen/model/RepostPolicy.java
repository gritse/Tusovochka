package smithereen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupFeatureState;
import smithereen.model.objects.Actor;

/**
 * Decides which posts and comments can be shared to someone's wall. A repost shows the original to everyone who can
 * see the reposter's wall, so only content meant for everyone qualifies.
 * <p>
 * This looks at the content only, not at who reposts or views it. {@code WallController} additionally checks that the
 * reposter can see the post and isn't blocked, and checks every viewer again when a repost is displayed.
 */
public final class RepostPolicy {
	private RepostPolicy() {}

	public enum Verdict {
		ALLOWED,
		/** The post, or the top-level post of a comment, is not public. */
		NOT_PUBLIC,
		/** The post is on the wall of a closed or private group. */
		GROUP_NOT_OPEN,
		/** The post is on the wall of a suspended, hidden or deactivated group. */
		GROUP_BANNED,
		/** The post is on a group wall that has been turned off. */
		GROUP_WALL_DISABLED,
		/** Someone wrote the post on a user's wall, and that user doesn't let everyone see others' posts on it. */
		OWNER_HIDES_OTHERS_POSTS,
	}

	/**
	 * Something to repost, together with what decides whether it may be reposted: the top-level post (the post itself,
	 * or for a comment the post it was written under) and the owner of the wall that post is on.
	 * <p>
	 * The constructor throws {@link IllegalArgumentException} when the three don't belong together or a post is deleted.
	 * That is a bug in the caller, not a reason to refuse, so it fails loudly. Use {@link #ofExisting} when the post or
	 * its top-level post may be gone.
	 */
	public record Candidate(@NotNull Post post, @NotNull Post topLevel, @NotNull Actor owner) {
		public Candidate {
			Objects.requireNonNull(post);
			Objects.requireNonNull(topLevel);
			Objects.requireNonNull(owner);
			if (post.isDeleted() || topLevel.isDeleted())
				throw new IllegalArgumentException("Post " + post.id + " or its top-level post is deleted");
			boolean isComment = post.getReplyLevel() > 0;
			int expectedTopLevelID = isComment ? post.replyKey.getFirst() : post.id;
			if (topLevel.id != expectedTopLevelID || topLevel.getReplyLevel() != 0)
				throw new IllegalArgumentException("Post " + topLevel.id + " is not the top-level post of " + post.id);
			if (post.ownerID != topLevel.ownerID || owner.getOwnerID() != topLevel.ownerID)
				throw new IllegalArgumentException("Post " + post.id + " is not on the wall of " + owner.getOwnerID());
		}

		/**
		 * @param topLevel The post itself for a top-level post, the post it was written under for a comment.
		 * @return The candidate, or empty when there is nothing left to repost: the post or its top-level post is
		 * deleted or wasn't found, or the wall owner wasn't found.
		 */
		public static Optional<Candidate> ofExisting(@NotNull Post post, @Nullable Post topLevel, @Nullable Actor owner) {
			if (post.isDeleted() || topLevel == null || topLevel.isDeleted() || owner == null)
				return Optional.empty();
			return Optional.of(new Candidate(post, topLevel, owner));
		}
	}

	public static Verdict check(@NotNull Candidate candidate) {
		if (!isPublic(candidate.post()) || !isPublic(candidate.topLevel()))
			return Verdict.NOT_PUBLIC;
		return switch (candidate.owner()) {
			case Group group -> checkGroupWall(group);
			case User user -> checkUserWall(user, candidate.topLevel());
		};
	}

	private static boolean isPublic(Post post) {
		return post.privacy == Post.Privacy.PUBLIC;
	}

	private static Verdict checkGroupWall(Group group) {
		if (group.accessType != Group.AccessType.OPEN)
			return Verdict.GROUP_NOT_OPEN;
		if (group.banStatus != GroupBanStatus.NONE)
			return Verdict.GROUP_BANNED;
		if (group.wallState == GroupFeatureState.DISABLED)
			return Verdict.GROUP_WALL_DISABLED;
		return Verdict.ALLOWED;
	}

	/**
	 * @param topLevel Decides for its comments too: a comment under a post someone wrote on this wall is as visible as
	 *                 that post. Exceptions in the owner's setting ("everyone except…") are applied per viewer when the
	 *                 repost is displayed.
	 */
	private static Verdict checkUserWall(User owner, Post topLevel) {
		boolean writtenByOwner = topLevel.authorID == owner.id;
		if (writtenByOwner)
			return Verdict.ALLOWED;
		PrivacySetting othersPostsVisibility = owner.getPrivacySetting(UserPrivacySettingKey.WALL_OTHERS_POSTS);
		if (othersPostsVisibility.baseRule != PrivacySetting.Rule.EVERYONE)
			return Verdict.OWNER_HIDES_OTHERS_POSTS;
		return Verdict.ALLOWED;
	}
}
