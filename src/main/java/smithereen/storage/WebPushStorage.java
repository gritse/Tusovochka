package smithereen.storage;

import java.sql.SQLException;
import java.util.List;

import smithereen.model.notifications.WebPushSubscription;
import smithereen.storage.sql.SQLQueryBuilder;

public class WebPushStorage {
	/**
	 * Saves a subscription. An endpoint belongs to one browser profile, so subscribing again from the same browser,
	 * possibly after logging in as someone else, moves the endpoint to the new account and updates its keys.
	 */
	public static void putSubscription(int accountID, String endpoint, byte[] p256dh, byte[] auth, String userAgent) throws SQLException {
		new SQLQueryBuilder()
				.insertInto("web_push_subscriptions")
				.value("account_id", accountID)
				.value("endpoint", endpoint)
				.value("key_p256dh", p256dh)
				.value("key_auth", auth)
				.value("user_agent", userAgent)
				.onDuplicateKeyUpdate()
				.executeNoResult();
	}

	public static List<WebPushSubscription> getSubscriptions(int accountID) throws SQLException {
		return new SQLQueryBuilder()
				.selectFrom("web_push_subscriptions")
				.allColumns()
				.where("account_id=?", accountID)
				.orderBy("created_at DESC")
				.executeAsStream(WebPushSubscription::fromResultSet)
				.toList();
	}

	public static void deleteSubscription(int accountID, String endpoint) throws SQLException {
		new SQLQueryBuilder()
				.deleteFrom("web_push_subscriptions")
				.where("account_id=? AND endpoint=?", accountID, endpoint)
				.executeNoResult();
	}

	public static void deleteSubscription(int id) throws SQLException {
		new SQLQueryBuilder()
				.deleteFrom("web_push_subscriptions")
				.where("id=?", id)
				.executeNoResult();
	}
}
