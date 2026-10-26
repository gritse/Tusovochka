package smithereen.model.apps;

import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumSet;

import smithereen.Utils;
import smithereen.storage.DatabaseUtils;

/**
 * A personal access token the user created in Settings for an AI agent, as shown in the token list. It is an
 * {@code api_tokens} row of the built-in agent application. {@code shortID} is derived from the token the same way
 * {@link smithereen.model.OtherSession#id()} is, so it can be used in URLs without exposing the token itself.
 */
public record AgentAccessToken(int shortID, byte[] fullID, String name, Instant createdAt, Instant expiresAt, Instant lastActive, InetAddress ip,
							   EnumSet<ClientAppPermission> permissions) {
	public static AgentAccessToken fromResultSet(ResultSet res) throws SQLException {
		byte[] id = res.getBytes("id");
		return new AgentAccessToken(
				(int) Utils.unpackLong(id),
				id,
				res.getString("name"),
				DatabaseUtils.getInstant(res, "created_at"),
				DatabaseUtils.getInstant(res, "expires_at"),
				DatabaseUtils.getInstant(res, "last_active"),
				Utils.deserializeInetAddress(res.getBytes("ip")),
				Utils.deserializeEnumSet(ClientAppPermission.class, res.getLong("permissions"))
		);
	}

	public boolean isExpired() {
		return expiresAt != null && expiresAt.isBefore(Instant.now());
	}
}
