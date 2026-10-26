package smithereen.storage;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import smithereen.Utils;
import smithereen.model.objects.Actor;
import smithereen.model.media.MediaFileRef;
import smithereen.model.apps.AgentAccessToken;
import smithereen.model.apps.AppAuthCode;
import smithereen.model.apps.AppAccessGrant;
import smithereen.model.apps.AppAccessToken;
import smithereen.model.apps.ClientApp;
import smithereen.model.apps.ClientAppPermission;
import smithereen.model.media.MediaFileRecord;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;
import smithereen.util.NamedMutexCollection;

public class AppsStorage{
	private static final Logger LOG=LoggerFactory.getLogger(AppsStorage.class);

	// region Apps

	public static ClientApp getAppByID(long id) throws SQLException{
		ClientApp app=new SQLQueryBuilder()
				.selectFrom("api_applications")
				.where("id=?", id)
				.executeAndGetSingleObject(ClientApp::fromResultSet);
		if(app!=null)
			postprocessApps(List.of(app));
		return app;
	}

	public static Map<Long, ClientApp> getAppsByIDs(Collection<Long> ids) throws SQLException{
		Map<Long, ClientApp> apps=new SQLQueryBuilder()
				.selectFrom("api_applications")
				.whereIn("id", ids)
				.executeAsStream(ClientApp::fromResultSet)
				.collect(Collectors.toMap(a->a.id, Function.identity()));
		postprocessApps(apps.values());
		return apps;
	}

	public static long createApp(int userID, String name, String description, MediaFileRef logo, String extraFields) throws SQLException{
		JsonObject serializedLogo=logo==null ? null : logo.toJson();
		return new SQLQueryBuilder()
				.insertInto("api_applications")
				.value("name", name)
				.value("description", description)
				.value("logo", serializedLogo==null ? null : serializedLogo.toString())
				.value("developer_id", userID)
				.value("extra", extraFields)
				.executeAndGetIDLong();
	}

	public static void updateApp(long id, String name, String description, MediaFileRef logo, String extraFields) throws SQLException{
		JsonObject serializedLogo=logo==null ? null : logo.toJson();
		new SQLQueryBuilder()
				.update("api_applications")
				.where("id=?", id)
				.value("name", name)
				.value("description", description)
				.value("logo", serializedLogo==null ? null : serializedLogo.toString())
				.value("extra", extraFields)
				.executeNoResult();
	}

	public static List<Long> getUserManagedApps(int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_applications")
				.columns("id")
				.where("developer_id=?", userID)
				.executeAndGetLongStream()
				.boxed()
				.toList();
	}

	private static void postprocessApps(Collection<ClientApp> apps) throws SQLException{
		MediaFileResolver.resolve(apps.stream().map(a->a.logo).filter(Objects::nonNull).toList());
	}

	public static long getAppIdByUsername(@NotNull String username) throws SQLException{
		if(username.length()>Actor.USERNAME_MAX_LENGTH)
			username=username.substring(0, Actor.USERNAME_MAX_LENGTH);
		return new SQLQueryBuilder()
				.selectFrom("api_applications")
				.columns("id")
				.where("username=?", username)
				.executeAndGetLong();
	}

	// endregion
	// region Access grants

	public static AppAccessGrant getAccessGrant(int accountID, long appID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_grants")
				.where("account_id=? AND app_id=?", accountID, appID)
				.executeAndGetSingleObject(AppAccessGrant::fromResultSet);
	}

	public static void createOrUpdateAccessGrant(int accountID, long appID, EnumSet<ClientAppPermission> permissions) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("api_grants")
				.value("account_id", accountID)
				.value("app_id", appID)
				.value("permissions", Utils.serializeEnumSet(permissions))
				.onDuplicateKeyUpdate()
				.executeNoResult();
	}

	public static void deleteAccessGrant(int accountID, long appID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("api_grants")
				.where("account_id=? AND app_id=?", accountID, appID)
				.executeNoResult();
	}

	public static List<AppAccessGrant> getAllAccessGrants(int accountID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_grants")
				.where("account_id=?", accountID)
				.orderBy("granted_at DESC")
				.executeAsStream(AppAccessGrant::fromResultSet)
				.toList();
	}

	// endregion
	// region Access tokens

	public static void createAccessToken(byte[] id, int accountID, long appID, InetAddress ip, Instant expiresAt, EnumSet<ClientAppPermission> permissions) throws SQLException{
		createAccessToken(id, accountID, appID, ip, expiresAt, permissions, null);
	}

	public static void createAccessToken(byte[] id, int accountID, long appID, InetAddress ip, Instant expiresAt, EnumSet<ClientAppPermission> permissions, String name) throws SQLException {
		new SQLQueryBuilder()
				.insertInto("api_tokens")
				.value("id", id)
				.value("account_id", accountID)
				.value("app_id", appID)
				.value("name", name)
				.value("ip", Utils.serializeInetAddress(ip))
				.value("user_agent", 0)
				.value("expires_at", expiresAt)
				.value("permissions", Utils.serializeEnumSet(permissions))
				.executeNoResult();
	}

	/**
	 * Lists the tokens of one application for one account with everything the Settings page shows, newest first.
	 */
	public static List<AgentAccessToken> getAgentAccessTokens(int accountID, long appID) throws SQLException {
		return new SQLQueryBuilder()
				.selectFrom("api_tokens")
				.allColumns()
				.where("account_id=? AND app_id=?", accountID, appID)
				.orderBy("created_at DESC")
				.executeAsStream(AgentAccessToken::fromResultSet)
				.toList();
	}

	public static AppAccessToken getAccessToken(byte[] id) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_tokens")
				.where("id=?", (Object)id)
				.executeAndGetSingleObject(AppAccessToken::fromResultSet);
	}

	public static void deleteAccessToken(byte[] id) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("api_tokens")
				.where("id=?", (Object)id)
				.executeNoResult();
	}

	public static void deleteAccessTokens(List<byte[]> ids) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("api_tokens")
				.whereIn("id", ids)
				.executeNoResult();
	}

	public static List<byte[]> getExpiredAccessTokens() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_tokens")
				.columns("id")
				.where("expires_at IS NOT NULL AND expires_at<CURRENT_TIMESTAMP()")
				.executeAsStream(r->r.getBytes(1))
				.toList();
	}

	public static void setAccessTokenLastUse(byte[] id, InetAddress ip, long uaHash) throws SQLException{
		new SQLQueryBuilder()
				.update("api_tokens")
				.valueExpr("last_active", "CURRENT_TIMESTAMP()")
				.value("ip", Utils.serializeInetAddress(ip))
				.value("user_agent", uaHash)
				.where("id=?", (Object)id)
				.executeNoResult();
	}

	public static List<byte[]> getUserAppAccessTokens(int accountID, long appID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_tokens")
				.columns("id")
				.where("account_id=? AND app_id=?", accountID, appID)
				.executeAsStream(r->r.getBytes(1))
				.toList();
	}

	public static List<byte[]> getAllUserAccessTokens(int accountID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("api_tokens")
				.columns("id")
				.where("account_id=?", accountID)
				.executeAsStream(r->r.getBytes(1))
				.toList();
	}

	// endregion
	// region OAuth auth codes

	public static void createAuthCode(byte[] id, int accountID, long appID, EnumSet<ClientAppPermission> permissions, Instant expiresAt, String extra) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("api_codes")
				.value("id", id)
				.value("account_id", accountID)
				.value("app_id", appID)
				.value("permissions", Utils.serializeEnumSet(permissions))
				.value("expires_at", expiresAt)
				.value("extra", extra)
				.executeNoResult();
	}

	public static AppAuthCode getAndDeleteAuthCode(byte[] id) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			AppAuthCode code=new SQLQueryBuilder(conn)
					.selectFrom("api_codes")
					.where("id=?", (Object)id)
					.executeAndGetSingleObject(AppAuthCode::fromResultSet);
			if(code==null)
				return null;
			new SQLQueryBuilder(conn)
					.deleteFrom("api_codes")
					.where("id=?", (Object)id)
					.executeNoResult();
			return code;
		}
	}

	public static void deleteExpiredCodes() throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("api_codes")
				.where("expires_at<CURRENT_TIMESTAMP()")
				.executeNoResult();
	}

	// endregion
	// region App installs

	public static boolean addAppUser(int userID, long appID) throws SQLException{
		return new SQLQueryBuilder()
				.insertIgnoreInto("api_app_installs")
				.value("user_id", userID)
				.value("app_id", appID)
				.executeUpdate()>0;
	}

	public static void removeAppUser(int userID, long appID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("api_app_installs")
				.where("user_id=? AND app_id=?", userID, appID)
				.executeNoResult();
	}

	// endregion
}
