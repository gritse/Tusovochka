package smithereen.model.notifications;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Locale;

import smithereen.lang.Lang;
import smithereen.model.feed.NewsfeedEntry;

import static org.junit.jupiter.api.Assertions.*;

public class PushNotificationTypeTest {
	@Test
	public void everyTypeHasATranslationAndAGroup() {
		Lang en = Lang.get(Locale.ENGLISH);
		Lang ru = Lang.get(Locale.forLanguageTag("ru"));
		for (PushNotificationType type : PushNotificationType.values()) {
			assertTrue(en.containsKey(type.getLangKey()), type + " has no English name");
			assertTrue(ru.containsKey(type.getLangKey()), type + " has no Russian name");
			if (type.getHintLangKey() != null) {
				assertTrue(en.containsKey(type.getHintLangKey()), type + " has no English hint");
				assertTrue(ru.containsKey(type.getHintLangKey()), type + " has no Russian hint");
			}
			assertNotNull(type.getGroup());
		}
	}

	@Test
	public void everySiteNotificationHasAPushSetting() {
		for (RealtimeNotificationSettingType type : RealtimeNotificationSettingType.values()) {
			assertNotNull(PushNotificationType.forRealtimeType(type));
		}
	}

	@Test
	public void feedEntriesMapToTheirFeedSettings() {
		assertEquals(PushNotificationType.FRIENDS_POSTS, PushNotificationType.forFriendsFeedEntry(NewsfeedEntry.Type.POST));
		assertEquals(PushNotificationType.FRIENDS_GROUPS, PushNotificationType.forFriendsFeedEntry(NewsfeedEntry.Type.CREATE_GROUP));
		assertNull(PushNotificationType.forFriendsFeedEntry(NewsfeedEntry.Type.GROUPED));
		assertEquals(PushNotificationType.GROUPS_POSTS, PushNotificationType.forGroupsFeedEntry(NewsfeedEntry.Type.POST));
		assertNull(PushNotificationType.forGroupsFeedEntry(NewsfeedEntry.Type.ADD_FRIEND));
		for (PushNotificationType type : PushNotificationType.values()) {
			if (type.getGroup() == PushNotificationType.Group.FRIENDS_FEED)
				assertNotNull(type.getFriendsFeedFilter(), type + " has no feed filter");
		}
	}

	@Test
	public void defaultsIncludeEverythingTheSiteNotifiesAbout() {
		EnumSet<PushNotificationType> defaults = PushNotificationType.defaultTypes();
		for (PushNotificationType type : PushNotificationType.values()) {
			if (type.getGroup() != PushNotificationType.Group.FRIENDS_FEED)
				assertTrue(defaults.contains(type), type + " is off by default");
		}
		assertFalse(defaults.contains(PushNotificationType.FRIENDS_PERSONAL_INFO));
	}
}
