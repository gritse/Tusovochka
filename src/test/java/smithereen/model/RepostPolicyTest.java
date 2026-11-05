package smithereen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import smithereen.model.RepostPolicy.Candidate;
import smithereen.model.RepostPolicy.Verdict;
import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupFeatureState;
import smithereen.model.objects.Actor;

import static org.junit.jupiter.api.Assertions.*;

public class RepostPolicyTest {
	private static final int ALICE = 1, BOB = 2, GROUP = 7;

	private static User user(int id) {
		User u = new User();
		u.id = id;
		return u;
	}

	private static User userWithOthersPostsVisibleTo(int id, PrivacySetting.Rule rule) {
		User u = user(id);
		PrivacySetting setting = new PrivacySetting();
		setting.baseRule = rule;
		u.privacySettings.put(UserPrivacySettingKey.WALL_OTHERS_POSTS, setting);
		return u;
	}

	private static Group group(Group.AccessType access) {
		Group g = new Group();
		g.id = GROUP;
		g.accessType = access;
		return g;
	}

	private static Post post(int id, int ownerID, int authorID) {
		Post p = new Post();
		p.id = id;
		p.ownerID = ownerID;
		p.authorID = authorID;
		return p;
	}

	private static Post comment(int id, Post topLevel, int authorID) {
		Post c = post(id, topLevel.ownerID, authorID);
		c.replyKey = List.of(topLevel.id);
		if (topLevel.ownerID != topLevel.authorID)
			c.flags.add(Post.Flag.TOP_IS_WALL_TO_WALL);
		return c;
	}

	private static Verdict checkPost(Post post, Actor owner) {
		return RepostPolicy.check(new Candidate(post, post, owner));
	}

	private static Verdict checkComment(Post comment, Post topLevel, Actor owner) {
		return RepostPolicy.check(new Candidate(comment, topLevel, owner));
	}

	// --- posts on users' walls

	@Test
	public void ownPostOnOwnWallIsAllowed() {
		assertEquals(Verdict.ALLOWED, checkPost(post(10, ALICE, ALICE), user(ALICE)));
	}

	@Test
	public void ownersSettingDoesNotAffectTheirOwnPosts() {
		assertEquals(Verdict.ALLOWED, checkPost(post(10, ALICE, ALICE), userWithOthersPostsVisibleTo(ALICE, PrivacySetting.Rule.NONE)));
	}

	@Test
	public void nonPublicPostIsRefused() {
		for (Post.Privacy privacy : Post.Privacy.values()) {
			if (privacy == Post.Privacy.PUBLIC)
				continue;
			Post p = post(10, ALICE, ALICE);
			p.privacy = privacy;
			assertEquals(Verdict.NOT_PUBLIC, checkPost(p, user(ALICE)), privacy.toString());
		}
	}

	@Test
	public void wallToWallPostIsAllowedWhenEveryoneSeesOthersPosts() {
		Post p = post(10, ALICE, BOB);
		assertEquals(Verdict.ALLOWED, checkPost(p, user(ALICE)), "the default is everyone");
		assertEquals(Verdict.ALLOWED, checkPost(p, userWithOthersPostsVisibleTo(ALICE, PrivacySetting.Rule.EVERYONE)));
	}

	@Test
	public void wallToWallPostIsRefusedWhenOwnerLimitsOthersPosts() {
		Post p = post(10, ALICE, BOB);
		for (PrivacySetting.Rule rule : PrivacySetting.Rule.values()) {
			if (rule == PrivacySetting.Rule.EVERYONE)
				continue;
			assertEquals(Verdict.OWNER_HIDES_OTHERS_POSTS, checkPost(p, userWithOthersPostsVisibleTo(ALICE, rule)), rule.toString());
		}
	}

	// --- posts on group walls

	@Test
	public void openGroupPostIsAllowed() {
		assertEquals(Verdict.ALLOWED, checkPost(post(10, -GROUP, ALICE), group(Group.AccessType.OPEN)));
	}

	@Test
	public void closedAndPrivateGroupPostsAreRefused() {
		assertEquals(Verdict.GROUP_NOT_OPEN, checkPost(post(10, -GROUP, ALICE), group(Group.AccessType.CLOSED)));
		assertEquals(Verdict.GROUP_NOT_OPEN, checkPost(post(10, -GROUP, ALICE), group(Group.AccessType.PRIVATE)));
	}

	@Test
	public void bannedGroupPostIsRefused() {
		for (GroupBanStatus status : GroupBanStatus.values()) {
			if (status == GroupBanStatus.NONE)
				continue;
			Group g = group(Group.AccessType.OPEN);
			g.banStatus = status;
			assertEquals(Verdict.GROUP_BANNED, checkPost(post(10, -GROUP, ALICE), g), status.toString());
		}
	}

	@Test
	public void disabledGroupWallIsRefused() {
		Group g = group(Group.AccessType.OPEN);
		g.wallState = GroupFeatureState.DISABLED;
		assertEquals(Verdict.GROUP_WALL_DISABLED, checkPost(post(10, -GROUP, ALICE), g));
	}

	@Test
	public void restrictedGroupWallIsAllowed() {
		// Only who can write is restricted, everyone can still read
		Group g = group(Group.AccessType.OPEN);
		g.wallState = GroupFeatureState.ENABLED_CLOSED;
		assertEquals(Verdict.ALLOWED, checkPost(post(10, -GROUP, ALICE), g));
	}

	// --- comments

	@Test
	public void commentFollowsItsTopLevelPost() {
		Post own = post(10, ALICE, ALICE);
		assertEquals(Verdict.ALLOWED, checkComment(comment(11, own, BOB), own, userWithOthersPostsVisibleTo(ALICE, PrivacySetting.Rule.NONE)));

		Post wallToWall = post(20, ALICE, BOB);
		assertEquals(Verdict.ALLOWED, checkComment(comment(21, wallToWall, BOB), wallToWall, user(ALICE)));
		assertEquals(Verdict.OWNER_HIDES_OTHERS_POSTS, checkComment(comment(21, wallToWall, ALICE), wallToWall, userWithOthersPostsVisibleTo(ALICE, PrivacySetting.Rule.FRIENDS)));

		Post groupPost = post(30, -GROUP, BOB);
		assertEquals(Verdict.ALLOWED, checkComment(comment(31, groupPost, ALICE), groupPost, group(Group.AccessType.OPEN)));
		assertEquals(Verdict.GROUP_NOT_OPEN, checkComment(comment(31, groupPost, ALICE), groupPost, group(Group.AccessType.CLOSED)));
	}

	@Test
	public void commentUnderNonPublicPostIsRefused() {
		Post friendsOnly = post(10, ALICE, ALICE);
		friendsOnly.privacy = Post.Privacy.FRIENDS_ONLY;
		assertEquals(Verdict.NOT_PUBLIC, checkComment(comment(11, friendsOnly, BOB), friendsOnly, user(ALICE)));
	}

	// --- inputs that don't belong together are a bug in the caller

	@Test
	public void topLevelPostMustBeThePostItself() {
		assertThrows(IllegalArgumentException.class, () -> new Candidate(post(10, ALICE, ALICE), post(11, ALICE, ALICE), user(ALICE)));
	}

	@Test
	public void commentNeedsItsOwnTopLevelPost() {
		Post closedGroupPost = post(30, -GROUP, BOB);
		Post comment = comment(31, closedGroupPost, ALICE);
		assertThrows(IllegalArgumentException.class, () -> new Candidate(comment, post(10, ALICE, ALICE), user(ALICE)), "another post");
		assertThrows(IllegalArgumentException.class, () -> new Candidate(comment, post(30, ALICE, ALICE), user(ALICE)), "same ID, other wall");
		assertThrows(IllegalArgumentException.class, () -> new Candidate(comment, comment, group(Group.AccessType.OPEN)), "the comment itself");
	}

	@Test
	public void ownerMustBeTheWallOwner() {
		assertThrows(IllegalArgumentException.class, () -> new Candidate(post(10, ALICE, ALICE), post(10, ALICE, ALICE), user(BOB)));
		Post groupPost = post(10, -GROUP, ALICE);
		assertThrows(IllegalArgumentException.class, () -> new Candidate(groupPost, groupPost, user(ALICE)), "the author is not the owner");
	}

	@Test
	public void deletedPostsAreNotCandidates() {
		Post deleted = post(10, ALICE, ALICE);
		deleted.deleted = true;
		assertThrows(IllegalArgumentException.class, () -> new Candidate(deleted, deleted, user(ALICE)));
		Post live = post(10, ALICE, ALICE);
		assertThrows(IllegalArgumentException.class, () -> new Candidate(comment(11, live, BOB), deleted, user(ALICE)));
	}

	// --- ofExisting: what callers use when things may be gone

	@Test
	public void ofExistingIsEmptyWhenSomethingIsGone() {
		Post live = post(10, ALICE, ALICE);
		Post deleted = post(10, ALICE, ALICE);
		deleted.deleted = true;
		assertTrue(Candidate.ofExisting(deleted, deleted, user(ALICE)).isEmpty(), "deleted post");
		assertTrue(Candidate.ofExisting(comment(11, live, BOB), null, user(ALICE)).isEmpty(), "top-level post not found");
		assertTrue(Candidate.ofExisting(comment(11, live, BOB), deleted, user(ALICE)).isEmpty(), "top-level post deleted");
		assertTrue(Candidate.ofExisting(live, live, null).isEmpty(), "owner not found");
		assertTrue(Candidate.ofExisting(live, live, user(ALICE)).isPresent());
	}

	@Test
	public void ofExistingStillRejectsMismatches() {
		Post live = post(10, ALICE, ALICE);
		assertThrows(IllegalArgumentException.class, () -> Candidate.ofExisting(live, live, user(BOB)));
	}
}
