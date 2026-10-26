package smithereen.mcp;

import com.google.gson.JsonObject;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import smithereen.model.apps.ClientAppPermission;

/**
 * A tool defined declaratively: a name, a description, an input schema, the scopes it needs, and a handler. The usual
 * handler forwards the arguments to one API method under the same parameter names, see {@link Builder#api(String)}.
 */
public final class ApiTool implements McpTool {
	public interface Handler {
		ToolResult call(ToolCallContext ctx, JsonObject args);
	}

	private final String name, title, description;
	private final JsonSchema schema;
	private final EnumSet<ClientAppPermission> scopes;
	private final boolean readOnly, destructive, idempotent;
	private final Handler handler;

	private ApiTool(Builder b) {
		name = b.name;
		title = Objects.requireNonNull(b.title, "title of " + b.name);
		description = Objects.requireNonNull(b.description, "description of " + b.name);
		schema = b.schema == null ? JsonSchema.object() : b.schema;
		scopes = b.scopes;
		readOnly = b.readOnly;
		destructive = b.destructive;
		idempotent = b.idempotent;
		handler = Objects.requireNonNull(b.handler, "handler of " + b.name);
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String title() {
		return title;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public JsonSchema schema() {
		return schema;
	}

	@Override
	public EnumSet<ClientAppPermission> requiredScopes() {
		return scopes;
	}

	@Override
	public boolean readOnly() {
		return readOnly;
	}

	@Override
	public boolean destructive() {
		return destructive;
	}

	@Override
	public boolean idempotent() {
		return idempotent;
	}

	@Override
	public ToolResult call(ToolCallContext ctx, JsonObject args) {
		return handler.call(ctx, args);
	}

	public static final class Builder {
		private final String name;
		private String title, description;
		private JsonSchema schema;
		private EnumSet<ClientAppPermission> scopes = EnumSet.noneOf(ClientAppPermission.class);
		private boolean readOnly, destructive, idempotent;
		private Handler handler;
		private final Map<String, Object> fixedParams = new HashMap<>();

		private Builder(String name) {
			this.name = name;
		}

		public Builder title(String title) {
			this.title = title;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder schema(JsonSchema schema) {
			this.schema = schema;
			return this;
		}

		public Builder scopes(ClientAppPermission first, ClientAppPermission... rest) {
			scopes = EnumSet.of(first, rest);
			return this;
		}

		public Builder readOnly() {
			readOnly = true;
			idempotent = true;
			return this;
		}

		public Builder destructive() {
			destructive = true;
			return this;
		}

		public Builder idempotent() {
			idempotent = true;
			return this;
		}

		/** A parameter the API method always gets, in addition to the tool's arguments. */
		public Builder fixed(String key, Object value) {
			fixedParams.put(key, value);
			return this;
		}

		public Builder call(Handler handler) {
			this.handler = handler;
			return this;
		}

		/**
		 * The common case: every argument of the schema is passed to the API method under the same name, plus the
		 * fixed parameters.
		 */
		public Builder api(String method) {
			Map<String, Object> fixed = Map.copyOf(fixedParams);
			handler = (ctx, args) -> {
				Map<String, Object> params = ToolCallContext.params(args, schema == null ? java.util.List.of() : schema.propertyNames());
				params.putAll(fixed);
				return ToolResult.ofApi(ctx.api(method, params));
			};
			return this;
		}

		public ApiTool build() {
			return new ApiTool(this);
		}
	}
}
