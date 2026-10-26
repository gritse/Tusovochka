package smithereen.controllers;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.LruCache;
import smithereen.Utils;
import smithereen.model.media.MediaFileRef;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.InvalidAccessTokenException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.model.Account;
import smithereen.model.User;
import smithereen.model.apps.AgentAccessToken;
import smithereen.model.apps.AppAuthCode;
import smithereen.model.apps.AppAccessGrant;
import smithereen.model.apps.AppAccessToken;
import smithereen.model.apps.ClientApp;
import smithereen.model.apps.ClientAppPermission;
import smithereen.model.media.MediaFileReferenceType;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.storage.AppsStorage;
import smithereen.storage.MediaStorage;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.SessionStorage;
import smithereen.util.ByteArrayMapKey;
import smithereen.util.JsonObjectBuilder;
import spark.Request;
import spark.utils.StringUtils;

public class AppsController{
	private static final Logger LOG=LoggerFactory.getLogger(AppsController.class);

	private final ApplicationContext context;
	private final SecureRandom srand=new SecureRandom();
	private final LruCache<ByteArrayMapKey, AppAccessToken> accessTokenCache=new LruCache<>(5000);
	private final LruCache<Long, ClientApp> appsCache=new LruCache<>(5000);
	private HashMap<ByteArrayMapKey, AccessTokenLastUseUpdate> accessTokenPendingAccessUpdates=new HashMap<>();
	private final Object accessTokenAccessUpdateLock=new Object();
	private final ScheduledExecutorService asyncUpdater;

	public AppsController(ApplicationContext context){
		this.context=context;
		asyncUpdater=Executors.newSingleThreadScheduledExecutor();
		asyncUpdater.submit(()->Thread.currentThread().setName("ApiTokenAccessUpdater"));
	}

	public void deleteExpiredCodesAndTokens(){
		try{
			List<byte[]> expiredTokenIDs=AppsStorage.getExpiredAccessTokens();
			if(!expiredTokenIDs.isEmpty()){
				AppsStorage.deleteAccessTokens(expiredTokenIDs);
			}
			AppsStorage.deleteExpiredCodes();
		}catch(SQLException x){
			LOG.error("Failed to delete expired auth codes and tokens", x);
		}
	}

	// region Apps

	public ClientApp getAppByID(long id){
		ClientApp app=appsCache.get(id);
		if(app!=null)
			return app;
		try{
			app=AppsStorage.getAppByID(id);
			if(app==null)
				throw new ObjectNotFoundException();
			appsCache.put(app.id, app);
			return app;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public Map<Long, ClientApp> getAppsByIDs(Collection<Long> ids){
		if(ids.isEmpty())
			return Map.of();
		try{
			HashMap<Long, ClientApp> res=new HashMap<>();
			HashSet<Long> remainingIDs=new HashSet<>();
			for(long id:ids){
				ClientApp app=appsCache.get(id);
				if(app!=null)
					res.put(app.id, app);
				else
					remainingIDs.add(id);
			}

			if(!remainingIDs.isEmpty()){
				Map<Long, ClientApp> moreApps=AppsStorage.getAppsByIDs(remainingIDs);
				moreApps.forEach(appsCache::put);
				res.putAll(moreApps);
			}

			return res;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public long createApp(User self, String name, String description, String logoID, Set<URI> redirectURIs){
		try{
			MediaFileRef logo=null;
			if(StringUtils.isNotEmpty(logoID)){
				logo=MediaStorageUtils.getMediaFileRef(logoID, MediaFileUploadPurpose.APP_LOGO, self.id);
			}
			ClientApp app=new ClientApp();
			app.allowedRedirectURIs=redirectURIs==null ? null : redirectURIs.stream().map(URI::toString).collect(Collectors.toCollection(LinkedHashSet::new));
			long id=AppsStorage.createApp(self.id, name, description, logo, app.serializeExtraFields());
			if(logo!=null){
				MediaStorage.createMediaFileReference(logo.fileID, id, MediaFileReferenceType.APP_LOGO, self.id);
			}
			return id;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void updateApp(User self, ClientApp app, String name, String description, String logoID, Set<URI> redirectURIs){
		try{
			MediaFileRef logo=app.logo;
			String existingLogoID=logo!=null ? logo.getLocalID(MediaFileUploadPurpose.APP_LOGO, self.id) : null;
			if(existingLogoID!=null && !existingLogoID.equals(logoID)){
				MediaStorage.deleteMediaFileReference(app.id, MediaFileReferenceType.APP_LOGO, app.logo.fileID);
			}
			if(StringUtils.isNotEmpty(logoID)){
				logo=MediaStorageUtils.getMediaFileRef(logoID, MediaFileUploadPurpose.APP_LOGO, self.id);
			}
			app.allowedRedirectURIs=redirectURIs==null ? null : redirectURIs.stream().map(URI::toString).collect(Collectors.toCollection(LinkedHashSet::new));
			AppsStorage.updateApp(app.id, name, description, logo, app.serializeExtraFields());
			if(!Objects.equals(logoID, existingLogoID) && logo!=null)
				MediaStorage.createMediaFileReference(logo.fileID, app.id, MediaFileReferenceType.APP_LOGO, app.developerID);
			appsCache.remove(app.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public List<Long> getUserManagedApps(User self){
		try{
			return AppsStorage.getUserManagedApps(self.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public long tryGetAppIdForUsername(@NotNull String username){
		try{
			return AppsStorage.getAppIdByUsername(username);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// endregion
	// region Access grants

	public AppAccessGrant getAccessGrantOrNull(Account account, ClientApp app){
		try{
			return AppsStorage.getAccessGrant(account.id, app.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public List<AppAccessGrant> getAllAccessGrants(Account account){
		try{
			return AppsStorage.getAllAccessGrants(account.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void deleteAccessGrantAndRevokeTokens(Account account, ClientApp app){
		try{
			AppAccessGrant grant=AppsStorage.getAccessGrant(account.id, app.id);
			if(grant==null)
				return;
			AppsStorage.deleteAccessGrant(account.id, app.id);
			removeAppUser(account.user, app);
			List<byte[]> tokens=AppsStorage.getUserAppAccessTokens(account.id, app.id);
			if(tokens.isEmpty())
				return;
			AppsStorage.deleteAccessTokens(tokens);
			for(byte[] token:tokens){
				accessTokenCache.remove(new ByteArrayMapKey(token));
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// endregion
	// region Access tokens

	/**
	 * Resolves a bearer token string as sent by an API or MCP client to the token and the account it belongs to,
	 * checking that it exists, has not expired and that the account is still allowed to act. Also records the use.
	 *
	 * @throws InvalidAccessTokenException with a message that is safe to show to the caller
	 */
	public AuthenticatedToken authenticateToken(String encodedToken, Request req) {
		byte[] tokenID;
		try {
			tokenID = Base64.getUrlDecoder().decode(encodedToken);
		} catch (IllegalArgumentException x) {
			throw new InvalidAccessTokenException("invalid access token");
		}
		if (tokenID.length != 64)
			throw new InvalidAccessTokenException("invalid access token");
		AppAccessToken token = getAccessTokenOrNull(tokenID);
		if (token == null)
			throw new InvalidAccessTokenException("invalid access token");
		if (token.expiresAt() != null && token.expiresAt().isBefore(Instant.now()))
			throw new InvalidAccessTokenException("access token has expired");
		Account account;
		try {
			account = context.getUsersController().getAccountOrThrow(token.accountID());
		} catch (ObjectNotFoundException x) {
			throw new InvalidAccessTokenException("invalid access token");
		}
		// Suspended, frozen, deactivated and unconfirmed accounts must not keep working through previously issued tokens
		if (!account.isActive())
			throw new InvalidAccessTokenException("account is not active");
		updateAccessTokenLastAccess(token, Utils.getRequestIP(req), Objects.requireNonNullElse(req.userAgent(), ""));
		return new AuthenticatedToken(token, account);
	}

	public record AuthenticatedToken(AppAccessToken token, Account account) {}

	// endregion
	// region Personal access tokens for AI agents

	/**
	 * The built-in application that personal access tokens created in Settings belong to. It is inserted by the
	 * schema migration, so it exists on every database that runs this code.
	 */
	public ClientApp getAgentApp() {
		if (Config.agentAppID == 0)
			throw new IllegalStateException("The agent application was not created, the database schema is out of date");
		return getAppByID(Config.agentAppID);
	}

	public boolean isAgentToken(AppAccessToken token) {
		return token != null && Config.agentAppID != 0 && token.appID() == Config.agentAppID;
	}

	public AppAccessToken createAgentToken(Account account, String name, EnumSet<ClientAppPermission> permissions, Instant expiresAt, Request req) {
		Objects.requireNonNull(expiresAt, "agent tokens always expire");
		ClientApp app = getAgentApp();
		try {
			byte[] id = new byte[64];
			srand.nextBytes(id);
			AppsStorage.createAccessToken(id, account.id, app.id, Utils.getRequestIP(req), expiresAt, permissions, name);
			AppAccessToken token = new AppAccessToken(id, account.id, app.id, expiresAt, permissions);
			accessTokenCache.put(new ByteArrayMapKey(id), token);
			return token;
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	public List<AgentAccessToken> getAgentTokens(Account account) {
		if (Config.agentAppID == 0)
			return List.of();
		try {
			return AppsStorage.getAgentAccessTokens(account.id, Config.agentAppID);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Revokes one of the account's own agent tokens by the short ID shown on the Settings page.
	 *
	 * @throws ObjectNotFoundException when the account has no such token
	 */
	public void revokeAgentToken(Account account, int shortID) {
		for (AgentAccessToken token : getAgentTokens(account)) {
			if (token.shortID() == shortID) {
				revokeAccessToken(token.fullID());
				return;
			}
		}
		throw new ObjectNotFoundException();
	}

	// endregion
	// region Access tokens (continued)

	public AppAccessToken createAccessToken(Account account, ClientApp app, EnumSet<ClientAppPermission> permissions, Request req){
		try{
			AppsStorage.createOrUpdateAccessGrant(account.id, app.id, permissions);
			addAppUser(account.user, app);
			Instant expiresAt=permissions.contains(ClientAppPermission.OFFLINE) || permissions.contains(ClientAppPermission.PASSWORD_GRANT_USED) ? null : Instant.now().plus(1, ChronoUnit.HOURS);
			byte[] id=new byte[64];
			srand.nextBytes(id);
			InetAddress ip=Utils.getRequestIP(req);
			AppsStorage.createAccessToken(id, account.id, app.id, ip, expiresAt, permissions);
			AppAccessToken token=new AppAccessToken(id, account.id, app.id, expiresAt, permissions);
			accessTokenCache.put(new ByteArrayMapKey(id), token);
			return token;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public AppAccessToken getAccessTokenOrNull(byte[] id){
		ByteArrayMapKey key=new ByteArrayMapKey(id);
		AppAccessToken token=accessTokenCache.get(key);
		if(token!=null)
			return token;
		try{
			token=AppsStorage.getAccessToken(id);
			if(token!=null)
				accessTokenCache.put(key, token);
			return token;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void updateAccessTokenLastAccess(AppAccessToken token, InetAddress ip, String userAgent){
		ByteArrayMapKey key=new ByteArrayMapKey(token.id());
		synchronized(accessTokenAccessUpdateLock){
			if(accessTokenPendingAccessUpdates.isEmpty()){
				asyncUpdater.schedule(this::doPendingTokenAccessUpdates, 10, TimeUnit.SECONDS);
			}
			accessTokenPendingAccessUpdates.put(key, new AccessTokenLastUseUpdate(ip, userAgent));
		}
	}

	public void revokeUserAccessTokensExcept(Account account, byte[] token){
		try{
			List<byte[]> tokensToRevoke=AppsStorage.getAllUserAccessTokens(account.id)
					.stream()
					.filter(t->!Arrays.equals(t, token))
					.toList();
			if(tokensToRevoke.isEmpty())
				return;
			AppsStorage.deleteAccessTokens(tokensToRevoke);
			for(byte[] t:tokensToRevoke){
				accessTokenCache.remove(new ByteArrayMapKey(t));
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void revokeAccessToken(byte[] token){
		try{
			AppsStorage.deleteAccessToken(token);
			accessTokenCache.remove(new ByteArrayMapKey(token));
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	private void doPendingTokenAccessUpdates(){
		HashMap<ByteArrayMapKey, AccessTokenLastUseUpdate> updates=accessTokenPendingAccessUpdates;
		synchronized(accessTokenAccessUpdateLock){
			accessTokenPendingAccessUpdates=new HashMap<>();
		}
		try{
			Map<String, Long> userAgents=updates.values().stream()
					.map(AccessTokenLastUseUpdate::userAgent)
					.distinct()
					.collect(Collectors.toMap(Function.identity(), Utils::hashUserAgent));
			SessionStorage.putUserAgents(userAgents);
			for(Map.Entry<ByteArrayMapKey, AccessTokenLastUseUpdate> e:updates.entrySet()){
				AppsStorage.setAccessTokenLastUse(e.getKey().key(), e.getValue().ip, userAgents.get(e.getValue().userAgent));
			}
		}catch(SQLException x){
			LOG.error("Failed to perform last token access updates", x);
		}
	}

	private record AccessTokenLastUseUpdate(InetAddress ip, String userAgent){}

	// endregion
	// region OAuth auth codes

	public byte[] createAuthCode(Account account, ClientApp app, EnumSet<ClientAppPermission> permissions, String s256CodeChallenge, String redirectURI){
		try{
			byte[] id=new byte[64];
			srand.nextBytes(id);
			JsonObjectBuilder jb=new JsonObjectBuilder()
					.add("redirectURI", redirectURI);
			if(StringUtils.isNotEmpty(s256CodeChallenge))
				jb.add("codeChallenge", s256CodeChallenge);
			AppsStorage.createAuthCode(id, account.id, app.id, permissions, Instant.now().plus(10, ChronoUnit.MINUTES), jb.build().toString());
			return id;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public AppAuthCode getAndDeleteAuthCode(byte[] id){
		try{
			return AppsStorage.getAndDeleteAuthCode(id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// endregion
	// region App installs

	public boolean addAppUser(User user, ClientApp app){
		try{
			return AppsStorage.addAppUser(user.id, app.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void removeAppUser(User user, ClientApp app){
		try{
			AppsStorage.removeAppUser(user.id, app.id);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	// endregion
}
