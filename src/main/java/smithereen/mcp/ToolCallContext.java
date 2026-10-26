package smithereen.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import smithereen.ApplicationContext;
import smithereen.api.ApiCallContext;
import smithereen.api.ApiDispatcher;
import smithereen.model.Account;
import smithereen.routes.ApiRoutes;

/**
 * Everything a tool needs to run: the application and the authenticated API call context of the agent's user.
 */
public class ToolCallContext {
	public final ApplicationContext ctx;
	public final ApiCallContext actx;

	public ToolCallContext(ApplicationContext ctx, ApiCallContext actx) {
		this.ctx = ctx;
		this.actx = actx;
	}

	public Account self() {
		return actx.self;
	}

	/**
	 * Calls a JSON API method as the agent's user and returns its response as JSON. Scopes, privacy, flood control
	 * and validation are all applied by the API; errors surface as {@link smithereen.api.ApiErrorException}.
	 */
	public JsonElement api(String method, Map<String, Object> params) {
		Object result = ApiDispatcher.doApiCall(method, ctx, new ApiCallContext(actx, params));
		return ApiRoutes.gson.toJsonTree(result);
	}

	public JsonElement api(String method) {
		return api(method, new HashMap<>());
	}

	/**
	 * Copies the named arguments that are present into API parameters. Arrays become comma-separated lists, which is
	 * what the API expects for ID lists, and {@code count} is capped at {@link JsonSchema#MAX_COUNT}.
	 */
	public static Map<String, Object> params(JsonObject args, List<String> keys) {
		HashMap<String, Object> params = new HashMap<>();
		for (String key : keys) {
			JsonElement value = args.get(key);
			if (value == null || value.isJsonNull())
				continue;
			params.put(key, toParam(key, value));
		}
		return params;
	}

	public static Object toParam(String key, JsonElement value) {
		if (value instanceof JsonArray arr) {
			return StreamSupport.stream(arr.spliterator(), false)
					.filter(e -> e instanceof JsonPrimitive)
					.map(JsonElement::getAsString)
					.collect(Collectors.joining(","));
		}
		if ("count".equals(key) && value instanceof JsonPrimitive p && p.isNumber()) {
			return Math.max(1, Math.min(JsonSchema.MAX_COUNT, p.getAsInt()));
		}
		return value;
	}

	/** Starts a parameter map by hand, for tools whose arguments do not map one to one onto an API method. */
	public static Map<String, Object> params() {
		return new HashMap<>();
	}
}
