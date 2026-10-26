package smithereen.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.api.ApiCallContext;
import smithereen.controllers.AppsController;
import smithereen.exceptions.FloodControlViolationException;
import smithereen.exceptions.InvalidAccessTokenException;
import smithereen.lang.Lang;
import smithereen.model.SizedImage;
import smithereen.routes.ApiRoutes;
import smithereen.util.FloodControl;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

import static smithereen.Utils.context;

/**
 * The Streamable HTTP binding of the MCP endpoint at {@code /mcp}: bearer-token authentication, the Origin check, the
 * protocol version header, and the JSON-RPC envelope. Responses are always a single JSON object; this server never
 * opens an SSE stream, so GET and DELETE are not supported.
 */
public class McpRoutes {
	public static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
	/** What a client that predates the header is assumed to speak, as the specification says. */
	private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";

	private static final McpServer server = new McpServer(ToolRegistry.withAllTools());

	public static McpServer server() {
		return server;
	}

	public static Object post(Request req, Response resp) {
		if (!Config.mcpEnabled) {
			resp.status(404);
			return "";
		}
		resp.type("application/json");

		String origin = req.headers("Origin");
		if (StringUtils.isNotEmpty(origin) && !isOwnOrigin(origin))
			return respond(resp, 403, McpServer.error(JsonNull.INSTANCE, McpServer.INVALID_REQUEST, "origin not allowed"));

		String protocolVersion = req.headers(PROTOCOL_VERSION_HEADER);
		if (StringUtils.isEmpty(protocolVersion))
			protocolVersion = DEFAULT_PROTOCOL_VERSION;
		if (!McpServer.SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
			return respond(resp, 400, McpServer.error(JsonNull.INSTANCE, McpServer.INVALID_REQUEST,
					"unsupported protocol version " + protocolVersion + ", this server speaks " + String.join(", ", McpServer.SUPPORTED_PROTOCOL_VERSIONS)));
		}

		String token = bearerToken(req);
		if (token == null) {
			resp.header("WWW-Authenticate", "Bearer realm=\"" + Config.domain + "\"");
			return respond(resp, 401, McpServer.error(JsonNull.INSTANCE, McpServer.INVALID_REQUEST, "an access token is required: Authorization: Bearer <token>; create one in Settings, Applications"));
		}
		ApplicationContext ctx = context(req);
		AppsController.AuthenticatedToken auth;
		try {
			auth = ctx.getAppsController().authenticateToken(token, req);
		} catch (InvalidAccessTokenException x) {
			resp.header("WWW-Authenticate", "Bearer realm=\"" + Config.domain + "\", error=\"invalid_token\"");
			return respond(resp, 401, McpServer.error(JsonNull.INSTANCE, McpServer.INVALID_REQUEST, x.getMessage()));
		}
		try {
			FloodControl.API_REQUESTS.incrementOrThrow(auth.token().id());
		} catch (FloodControlViolationException x) {
			resp.header("Retry-After", "1");
			return respond(resp, 429, McpServer.error(JsonNull.INSTANCE, McpServer.INVALID_REQUEST, "too many requests"));
		}

		JsonElement message;
		try {
			message = JsonParser.parseString(req.body());
		} catch (JsonParseException x) {
			return respond(resp, 400, McpServer.error(JsonNull.INSTANCE, McpServer.PARSE_ERROR, "the body is not valid JSON"));
		}

		ApiCallContext actx = new ApiCallContext(auth.token(), auth.account(), Map.of(), req, 1, 0, ctx.getUsersController().getUserPermissions(auth.account()));
		actx.imageFormat = SizedImage.Format.WEBP;
		actx.lang = Lang.get(auth.account().prefs.locale == null ? Locale.US : auth.account().prefs.locale);
		McpServer.HttpResult result = server.handle(message, new ToolCallContext(ctx, actx));
		if (result.body() == null) {
			resp.status(result.status());
			return "";
		}
		return respond(resp, result.status(), result.body());
	}

	public static Object methodNotAllowed(Request req, Response resp) {
		resp.status(Config.mcpEnabled ? 405 : 404);
		if (Config.mcpEnabled)
			resp.header("Allow", "POST");
		return "";
	}

	private static Object respond(Response resp, int status, JsonElement body) {
		resp.status(status);
		return ApiRoutes.gson.toJson(body);
	}

	/** The token comes from the Authorization header only; the specification forbids tokens in the query string. */
	private static String bearerToken(Request req) {
		String header = req.headers("Authorization");
		if (StringUtils.isEmpty(header))
			return null;
		String[] parts = header.trim().split("\\s+", 2);
		if (parts.length != 2 || !parts[0].equalsIgnoreCase("bearer") || parts[1].isEmpty())
			return null;
		return parts[1];
	}

	/** Browsers send Origin; a value other than this server's own origin means a cross-site or DNS-rebinding request. */
	static boolean isOwnOrigin(String origin) {
		return isOwnOrigin(origin, Config.localURI("/"));
	}

	static boolean isOwnOrigin(String origin, URI own) {
		try {
			URI uri = new URI(origin.trim());
			return uri.getScheme() != null && uri.getScheme().equalsIgnoreCase(own.getScheme())
					&& uri.getHost() != null && uri.getHost().equalsIgnoreCase(own.getHost())
					&& uri.getPort() == own.getPort();
		} catch (Exception x) {
			return false;
		}
	}
}
