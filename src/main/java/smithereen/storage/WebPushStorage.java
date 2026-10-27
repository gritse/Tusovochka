package smithereen.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import smithereen.model.notifications.WebPushSubscription;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
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

	/**
	 * Narrows a list of users down to those with at least one device, so a fan-out to a whole group or friend list
	 * only loads the accounts it can actually reach.
	 *
	 * @return user ID to account ID
	 */
	public static Map<Integer, Integer> getAccountIDsWithSubscriptions(Collection<Integer> userIDs) throws SQLException {
		if (userIDs.isEmpty())
			return Map.of();
		try (DatabaseConnection conn = DatabaseConnectionManager.getConnection()) {
			PreparedStatement stmt = SQLQueryBuilder.prepareStatement(conn, "SELECT DISTINCT a.user_id, a.id FROM accounts a JOIN web_push_subscriptions s ON s.account_id=a.id WHERE a.user_id IN ("
					+ String.join(",", Collections.nCopies(userIDs.size(), "?")) + ")", userIDs.toArray());
			HashMap<Integer, Integer> result = new HashMap<>();
			try (ResultSet res = stmt.executeQuery()) {
				while (res.next()) {
					result.put(res.getInt(1), res.getInt(2));
				}
			}
			return result;
		}
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
