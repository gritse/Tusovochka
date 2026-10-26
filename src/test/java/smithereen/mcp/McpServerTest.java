package smithereen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import smithereen.api.ApiCallContext;
import smithereen.api.ApiErrorException;
import smithereen.api.model.ApiError;
import smithereen.api.model.ApiErrorType;
import smithereen.model.apps.AppAccessToken;
import smithereen.model.apps.ClientAppPermission;

import static org.junit.jupiter.api.Assertions.*;

public class McpServerTest {
	private static final McpTool ECHO = ApiTool.builder("echo")
			.title("Echo").description("Returns its arguments")
			.schema(JsonSchema.object().string("text", "What to echo").integer("n", "A number").required("text"))
			.readOnly()
			.call((ctx, args) -> ToolResult.ofApi(args))
			.build();
	private static final McpTool WRITE = ApiTool.builder("write_thing")
			.title("Write").description("Needs wall:write")
			.scopes(ClientAppPermission.WALL_WRITE)
			.call((ctx, args) -> ToolResult.text("written"))
			.build();
	private static final McpTool API_FAILURE = ApiTool.builder("api_failure")
			.title("Fails").description("Throws an API error")
			.readOnly()
			.call((ctx, args) -> {
				throw new ApiErrorException(new ApiError(ApiErrorType.NOT_FOUND, "no such post", Map.of()));
			})
			.build();
	private static final McpTool CRASH = ApiTool.builder("crash")
			.title("Crashes").description("Throws")
			.readOnly()
			.call((ctx, args) -> {
				throw new IllegalStateException("boom");
			})
			.build();

	private final McpServer server = new McpServer(new ToolRegistry(List.of(ECHO, WRITE, API_FAILURE, CRASH)));

	private static ToolCallContext context(EnumSet<ClientAppPermission> permissions) {
		byte[] id = new byte[64];
		new SecureRandom().nextBytes(id);
		AppAccessToken token = new AppAccessToken(id, 1, 1, null, permissions);
		return new ToolCallContext(null, new ApiCallContext(token, null, Map.of(), null, 1, 0, null));
	}

	private McpServer.HttpResult send(String json, EnumSet<ClientAppPermission> permissions) {
		return server.handle(JsonParser.parseString(json), context(permissions));
	}

	private McpServer.HttpResult send(String json) {
		return send(json, EnumSet.noneOf(ClientAppPermission.class));
	}

	private static JsonObject body(McpServer.HttpResult result) {
		return result.body().getAsJsonObject();
	}

	@Test
	public void initializeNegotiatesTheProtocolVersion() {
		McpServer.HttpResult r = send("""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""");
		assertEquals(200, r.status());
		JsonObject result = body(r).getAsJsonObject("result");
		assertEquals(1, body(r).get("id").getAsInt());
		assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
		assertFalse(result.getAsJsonObject("capabilities").getAsJsonObject("tools").get("listChanged").getAsBoolean());
		assertEquals(McpServer.SERVER_NAME, result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.get("instructions").getAsString().contains("get_me"));

		r = send("""
				{"jsonrpc":"2.0","id":"abc","method":"initialize","params":{"protocolVersion":"1999-01-01"}}""");
		assertEquals(McpServer.LATEST_PROTOCOL_VERSION, body(r).getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals("abc", body(r).get("id").getAsString());
	}

	@Test
	public void pingAndNotifications() {
		McpServer.HttpResult r = send("""
				{"jsonrpc":"2.0","id":2,"method":"ping"}""");
		assertEquals(200, r.status());
		assertEquals(0, body(r).getAsJsonObject("result").size());

		r = send("""
				{"jsonrpc":"2.0","method":"notifications/initialized"}""");
		assertEquals(202, r.status());
		assertNull(r.body());
	}

	@Test
	public void rejectsMalformedMessages() {
		McpServer.HttpResult r = send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
		assertEquals(400, r.status());
		assertEquals(McpServer.INVALID_REQUEST, body(r).getAsJsonObject("error").get("code").getAsInt());
		assertTrue(body(r).get("id").isJsonNull());

		r = send("{\"id\":1,\"method\":\"ping\"}");
		assertEquals(400, r.status());

		r = send("{\"jsonrpc\":\"2.0\",\"id\":1}");
		assertEquals(400, r.status());
		assertEquals(1, body(r).get("id").getAsInt());

		r = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
		assertEquals(400, r.status());
	}

	@Test
	public void unknownMethodsAndModernProbes() {
		McpServer.HttpResult r = send("""
				{"jsonrpc":"2.0","id":3,"method":"resources/list"}""");
		assertEquals(200, r.status());
		assertEquals(McpServer.METHOD_NOT_FOUND, body(r).getAsJsonObject("error").get("code").getAsInt());

		// A 2026-07-28 client probing with per-request metadata must get a 4xx without a modern error body
		r = send("""
				{"jsonrpc":"2.0","id":4,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""");
		assertEquals(400, r.status());
		assertEquals(McpServer.METHOD_NOT_FOUND, body(r).getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void toolsListIsFilteredByScopes() {
		McpServer.HttpResult r = send("""
				{"jsonrpc":"2.0","id":5,"method":"tools/list"}""");
		JsonArray tools = body(r).getAsJsonObject("result").getAsJsonArray("tools");
		assertEquals(List.of("echo", "api_failure", "crash"), tools.asList().stream().map(t -> t.getAsJsonObject().get("name").getAsString()).toList());
		JsonObject echo = tools.get(0).getAsJsonObject();
		assertEquals("Echo", echo.get("title").getAsString());
		assertTrue(echo.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
		assertEquals("text", echo.getAsJsonObject("inputSchema").getAsJsonArray("required").get(0).getAsString());

		r = send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}", EnumSet.of(ClientAppPermission.WALL_WRITE));
		assertEquals(4, body(r).getAsJsonObject("result").getAsJsonArray("tools").size());

		r = send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}", EnumSet.of(ClientAppPermission.PASSWORD_GRANT_USED));
		assertEquals(4, body(r).getAsJsonObject("result").getAsJsonArray("tools").size());
	}

	@Test
	public void toolsCallValidatesAndRuns() {
		McpServer.HttpResult r = send("""
				{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"nope","arguments":{}}}""");
		assertEquals(200, r.status());
		assertEquals(McpServer.INVALID_PARAMS, body(r).getAsJsonObject("error").get("code").getAsInt());

		r = send("""
				{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"echo","arguments":{"n":1}}}""");
		assertEquals(McpServer.INVALID_PARAMS, body(r).getAsJsonObject("error").get("code").getAsInt());
		assertTrue(body(r).getAsJsonObject("error").get("message").getAsString().contains("text"));

		r = send("""
				{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi","n":1}}}""");
		JsonObject result = body(r).getAsJsonObject("result");
		assertFalse(result.has("isError"));
		assertEquals("hi", result.getAsJsonObject("structuredContent").get("text").getAsString());
		assertEquals("text", result.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
		assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("\"hi\""));
	}

	@Test
	public void failuresBecomeErrorResultsTheModelCanReadButScopesAreEnforced() {
		McpServer.HttpResult r = send("""
				{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"write_thing"}}""");
		JsonObject result = body(r).getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("wall:write"));

		r = send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"write_thing\"}}", EnumSet.of(ClientAppPermission.WALL_WRITE));
		assertFalse(body(r).getAsJsonObject("result").has("isError"));

		r = send("""
				{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"api_failure"}}""");
		result = body(r).getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		assertEquals("not_found", result.getAsJsonObject("structuredContent").getAsJsonObject("error").get("code").getAsString());
		assertTrue(result.getAsJsonObject("structuredContent").getAsJsonObject("error").get("message").getAsString().contains("no such post"));

		r = send("""
				{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"crash"}}""");
		assertEquals(200, r.status());
		assertEquals("internal_error", body(r).getAsJsonObject("result").getAsJsonObject("structuredContent").getAsJsonObject("error").get("code").getAsString());
	}

	@Test
	public void writesAreRateLimitedPerToken() {
		ToolCallContext ctx = context(EnumSet.of(ClientAppPermission.WALL_WRITE));
		JsonElement call = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"write_thing\"}}");
		for (int i = 0; i < 60; i++) {
			assertFalse(body(server.handle(call, ctx)).getAsJsonObject("result").has("isError"), "call " + i);
		}
		JsonObject result = body(server.handle(call, ctx)).getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		assertEquals("flood_control", result.getAsJsonObject("structuredContent").getAsJsonObject("error").get("code").getAsString());
		// Reads are not affected
		JsonElement read = JsonParser.parseString("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}}}");
		assertFalse(body(server.handle(read, ctx)).getAsJsonObject("result").has("isError"));
	}

	@Test
	public void originCheck() {
		URI own = URI.create("https://social.example.com/");
		assertTrue(McpRoutes.isOwnOrigin("https://social.example.com", own));
		assertTrue(McpRoutes.isOwnOrigin("HTTPS://Social.Example.com", own));
		assertFalse(McpRoutes.isOwnOrigin("http://social.example.com", own));
		assertFalse(McpRoutes.isOwnOrigin("https://evil.example.com", own));
		assertFalse(McpRoutes.isOwnOrigin("https://social.example.com:8443", own));
		assertFalse(McpRoutes.isOwnOrigin("null", own));
		assertFalse(McpRoutes.isOwnOrigin("not a url", own));
		URI local = URI.create("http://localhost:4567/");
		assertTrue(McpRoutes.isOwnOrigin("http://localhost:4567", local));
		assertFalse(McpRoutes.isOwnOrigin("http://localhost", local));
	}
}
