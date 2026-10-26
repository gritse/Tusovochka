package smithereen.mcp;

import com.google.gson.JsonObject;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import smithereen.controllers.AppsController;

/**
 * The event stream at {@code /mcp/events}. The bearer token is checked by a Spark filter before the upgrade
 * ({@link McpRoutes#authenticateEventStream}), which leaves the result in a request attribute. The client sends any
 * text message as a keep-alive at least once a minute; everything it sends is otherwise ignored.
 */
@WebSocket
public class AgentEventsWebSocket {
	private static final Logger LOG = LoggerFactory.getLogger(AgentEventsWebSocket.class);
	static final String AUTH_ATTRIBUTE = "agentEventsAuth";

	@OnWebSocketConnect
	public void onConnect(Session session) {
		if (!(session.getUpgradeRequest() instanceof JettyServerUpgradeRequest req)
				|| !(req.getServletAttribute(AUTH_ATTRIBUTE) instanceof AppsController.AuthenticatedToken auth)) {
			session.close(4001, "not authenticated");
			return;
		}
		session.setIdleTimeout(Duration.ofSeconds(90));
		AgentEvents.Connection conn = new AgentEvents.Connection(auth.account().user.id, auth.token(), session);
		AgentEvents.getInstance().register(conn);
		JsonObject hello = new JsonObject();
		hello.addProperty("type", "hello");
		hello.addProperty("user_id", auth.account().user.id);
		hello.addProperty("name", auth.account().user.getFullName());
		conn.send(hello.toString());
	}

	@OnWebSocketClose
	public void onClose(Session session, int statusCode, String reason) {
		AgentEvents.getInstance().unregister(session);
	}

	@OnWebSocketMessage
	public void onMessage(Session session, String message) {
		// Keep-alive, nothing to do
	}

	@OnWebSocketError
	public void onError(Session session, Throwable err) {
		LOG.debug("Agent event stream error on {}", session, err);
	}
}
