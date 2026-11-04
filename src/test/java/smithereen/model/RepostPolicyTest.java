package smithereen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import smithereen.model.groups.GroupBanStatus;
import smithereen.model.groups.GroupFeatureState;

import static org.junit.jupiter.api.Assertions.*;

public class RepostPolicyTest {
	private static final int ALICE = 1, BOB = 2, GROUP = 7;

	private static User user(int id) {
		User u = new User();
		u.id = id;
		return u;
	}

	private static User userHidingOthersPosts(int id, PrivacySetting.Rule rule) {
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

	@Test
	public void ownPostOnOwnWall() {
		assertTrue(RepostPolicy.canBeReposted(post(10, ALICE, ALICE), null, user(ALICE)));
	}

	@Test
	public void nonPublicPostIsRefused() {
		for (Post.Privacy privacy : Post.Privacy.values()) {
			if (privacy == Post.Privacy.PUBLIC)
				continue;
			Post p = post(10, ALICE, ALICE);
			p.privacy = privacy;
			assertFalse(RepostPolicy.canBeReposted(p, null, user(ALICE)), privacy.toString());
		}
	}

	@Test
	public void deletedOrMissingIsRefused() {
		Post p = post(10, ALICE, ALICE);
		p.deleted = true;
		assertFalse(RepostPolicy.canBeReposted(p, null, user(ALICE)));
		assertFalse(RepostPolicy.canBeReposted(null, null, user(ALICE)));
		assertFalse(RepostPolicy.canBeReposted(post(10, ALICE, ALICE), null, null));
	}

	@Test
	public void ownerMustMatchThePostsWall() {
		assertFalse(RepostPolicy.canBeReposted(post(10, ALICE, ALICE), null, user(BOB)));
		assertFalse(RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, user(ALICE)));
	}

	@Test
	public void openGroupPostIsAllowed() {
		assertTrue(RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, group(Group.AccessType.OPEN)));
	}

	@Test
	public void closedAndPrivateGroupPostsAreRefused() {
		assertFalse(RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, group(Group.AccessType.CLOSED)));
		assertFalse(RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, group(Group.AccessType.PRIVATE)));
	}

	@Test
	public void bannedGroupOrDisabledWallIsRefused() {
		for (GroupBanStatus status : GroupBanStatus.values()) {
			Group g = group(Group.AccessType.OPEN);
			g.banStatus = status;
			assertEquals(status == GroupBanStatus.NONE, RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, g), status.toString());
		}
		Group g = group(Group.AccessType.OPEN);
		g.wallState = GroupFeatureState.DISABLED;
		assertFalse(RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, g));
		g.wallState = GroupFeatureState.ENABLED_CLOSED; // only who can write is restricted, reading is open
		assertTrue(RepostPolicy.canBeReposted(post(10, -GROUP, ALICE), null, g));
	}

	@Test
	public void wallToWallPostDependsOnOwnersSetting() {
		Post p = post(10, ALICE, BOB);
		assertTrue(RepostPolicy.canBeReposted(p, null, user(ALICE)), "default is everyone");
		assertTrue(RepostPolicy.canBeReposted(p, null, userHidingOthersPosts(ALICE, PrivacySetting.Rule.EVERYONE)));
		for (PrivacySetting.Rule rule : PrivacySetting.Rule.values()) {
			if (rule == PrivacySetting.Rule.EVERYONE)
				continue;
			assertFalse(RepostPolicy.canBeReposted(p, null, userHidingOthersPosts(ALICE, rule)), rule.toString());
		}
	}

	@Test
	public void ownersSettingDoesNotAffectTheirOwnPosts() {
		assertTrue(RepostPolicy.canBeReposted(post(10, ALICE, ALICE), null, userHidingOthersPosts(ALICE, PrivacySetting.Rule.NONE)));
	}

	@Test
	public void commentFollowsItsTopLevelPost() {
		Post own = post(10, ALICE, ALICE);
		assertTrue(RepostPolicy.canBeReposted(comment(11, own, BOB), own, user(ALICE)));

		Post wallToWall = post(20, ALICE, BOB);
		assertTrue(RepostPolicy.canBeReposted(comment(21, wallToWall, BOB), wallToWall, user(ALICE)));
		assertFalse(RepostPolicy.canBeReposted(comment(21, wallToWall, BOB), wallToWall, userHidingOthersPosts(ALICE, PrivacySetting.Rule.FRIENDS)));

		Post groupPost = post(30, -GROUP, BOB);
		assertTrue(RepostPolicy.canBeReposted(comment(31, groupPost, ALICE), groupPost, group(Group.AccessType.OPEN)));
		assertFalse(RepostPolicy.canBeReposted(comment(31, groupPost, ALICE), groupPost, group(Group.AccessType.CLOSED)));
	}

	@Test
	public void commentIsJudgedOnlyByItsOwnThread() {
		Post closedGroupPost = post(30, -GROUP, BOB);
		Post comment = comment(31, closedGroupPost, ALICE);
		Post unrelated = post(10, ALICE, ALICE);
		assertFalse(RepostPolicy.canBeReposted(comment, unrelated, user(ALICE)), "another post passed as the thread");
		Post sameIDOtherWall = post(30, ALICE, ALICE);
		assertFalse(RepostPolicy.canBeReposted(comment, sameIDOtherWall, user(ALICE)), "thread post on a different wall");
	}

	@Test
	public void commentNeedsAVisibleTopLevelPost() {
		Post own = post(10, ALICE, ALICE);
		assertFalse(RepostPolicy.canBeReposted(comment(11, own, BOB), null, user(ALICE)), "top-level post missing");
		Post friendsOnly = post(10, ALICE, ALICE);
		friendsOnly.privacy = Post.Privacy.FRIENDS_ONLY;
		assertFalse(RepostPolicy.canBeReposted(comment(11, friendsOnly, BOB), friendsOnly, user(ALICE)));
		Post deleted = post(10, ALICE, ALICE);
		deleted.deleted = true;
		assertFalse(RepostPolicy.canBeReposted(comment(11, own, BOB), deleted, user(ALICE)));
	}
}
