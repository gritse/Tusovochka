package smithereen.model.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import smithereen.storage.DatabaseUtils;
import smithereen.util.uaparser.BrowserInfo;
import smithereen.util.uaparser.UserAgentParser;

/**
 * A browser's push subscription, as returned by {@code PushManager.subscribe()} on one device of one account.
 *
 * @param endpoint the push service URL the encrypted messages are POSTed to
 * @param p256dh   the browser's public key, 65 bytes
 * @param auth     the browser's authentication secret, 16 bytes
 */
public record WebPushSubscription(int id, int accountID, String endpoint, byte[] p256dh, byte[] auth, String userAgent, Instant createdAt) {
	public static WebPushSubscription fromResultSet(ResultSet res) throws SQLException {
		return new WebPushSubscription(
				res.getInt("id"),
				res.getInt("account_id"),
				res.getString("endpoint"),
				res.getBytes("key_p256dh"),
				res.getBytes("key_auth"),
				res.getString("user_agent"),
				DatabaseUtils.getInstant(res, "created_at")
		);
	}

	public BrowserInfo browserInfo() {
		return UserAgentParser.parse(userAgent);
	}
}
