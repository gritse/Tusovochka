package smithereen.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.http.ExtendedHttpClient;
import smithereen.model.Account;
import smithereen.model.notifications.WebPushSubscription;
import smithereen.storage.WebPushStorage;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.WebPush;
import spark.utils.StringUtils;

/**
 * Browser push notifications (Web Push with VAPID). For now this only stores subscriptions and sends a test
 * notification from Settings; nothing in the app sends real notifications through it yet.
 */
public class WebPushController {
	private static final Logger LOG = LoggerFactory.getLogger(WebPushController.class);
	/**
	 * The push services of the browsers we know. The server POSTs to whatever endpoint a browser gives it, so endpoints
	 * are limited to these hosts rather than letting a user point the server at arbitrary URLs.
	 */
	private static final Set<String> PUSH_SERVICE_HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com");
	// Chromium builds without Google API keys get endpoints like jmt17.google.com instead of fcm.googleapis.com
	private static final List<String> PUSH_SERVICE_HOST_SUFFIXES = List.of(".google.com", ".googleapis.com", ".push.apple.com", ".notify.windows.com");

	private final ApplicationContext context;
	private final HttpClient httpClient = ExtendedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public WebPushController(ApplicationContext context) {
		this.context = context;
	}

	/**
	 * What happened to one subscription when a notification was sent to it.
	 *
	 * @param status  the push service's HTTP status, or 0 if the request did not get through
	 * @param removed whether the push service said the subscription is gone, so it was deleted
	 */
	public record DeliveryResult(WebPushSubscription subscription, int status, String error, boolean removed) {
		public boolean delivered() {
			return status / 100 == 2;
		}
	}

	public static boolean isAllowedEndpoint(String endpoint) {
		try {
			URI uri = new URI(endpoint);
			if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1)
				return false;
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			return PUSH_SERVICE_HOSTS.contains(host) || PUSH_SERVICE_HOST_SUFFIXES.stream().anyMatch(host::endsWith);
		} catch (URISyntaxException x) {
			return false;
		}
	}

	public void subscribe(Account self, String endpoint, String p256dh, String auth, String userAgent) {
		if (StringUtils.isEmpty(endpoint) || endpoint.length() > 1000 || !isAllowedEndpoint(endpoint))
			throw new BadRequestException("Unsupported push service");
		byte[] key, secret;
		try {
			key = Base64.getUrlDecoder().decode(p256dh);
			secret = Base64.getUrlDecoder().decode(auth);
			WebPush.decodePublicKey(key);
		} catch (IllegalArgumentException | NullPointerException | GeneralSecurityException x) {
			throw new BadRequestException("Invalid subscription keys");
		}
		if (secret.length != 16)
			throw new BadRequestException("Invalid subscription keys");
		if (userAgent != null && userAgent.length() > 300)
			userAgent = userAgent.substring(0, 300);
		try {
			WebPushStorage.putSubscription(self.id, endpoint, key, secret, userAgent);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	public void unsubscribe(Account self, String endpoint) {
		try {
			WebPushStorage.deleteSubscription(self.id, endpoint);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	public List<WebPushSubscription> getSubscriptions(Account self) {
		try {
			return WebPushStorage.getSubscriptions(self.id);
		} catch (SQLException x) {
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Sends a notification to every device of an account and waits for the push services to answer.
	 * The service worker ({@code /sw.js}) shows {@code title} and {@code body} and opens {@code url} when clicked.
	 */
	public List<DeliveryResult> sendToAccount(Account account, String title, String body, String url) {
		byte[] payload = new JsonObjectBuilder()
				.add("title", title)
				.add("body", body)
				.add("url", url)
				.build()
				.toString()
				.getBytes(StandardCharsets.UTF_8);
		List<DeliveryResult> results = new ArrayList<>();
		for (WebPushSubscription sub : getSubscriptions(account)) {
			results.add(send(sub, payload));
		}
		return results;
	}

	private DeliveryResult send(WebPushSubscription sub, byte[] payload) {
		try {
			URI endpoint = URI.create(sub.endpoint());
			HttpRequest req = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(15))
					.header("Authorization", WebPush.vapidAuthorization(endpoint, Config.webPushPrivateKey, Config.webPushPublicKey, vapidSubject(),
							Instant.now().plus(12, ChronoUnit.HOURS)))
					.header("Content-Encoding", "aes128gcm")
					.header("Content-Type", "application/octet-stream")
					.header("TTL", String.valueOf(24 * 60 * 60))
					.header("Urgency", "high")
					.POST(HttpRequest.BodyPublishers.ofByteArray(WebPush.encrypt(payload, sub.p256dh(), sub.auth())))
					.build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			int status = resp.statusCode();
			if (status / 100 == 2)
				return new DeliveryResult(sub, status, null, false);
			LOG.debug("Push service returned {} for subscription {}: {}", status, sub.id(), resp.body());
			// 404 and 410 mean the browser unsubscribed or the subscription expired
			boolean gone = status == 404 || status == 410;
			if (gone)
				WebPushStorage.deleteSubscription(sub.id());
			String error = resp.body();
			if (error != null && error.length() > 300)
				error = error.substring(0, 300);
			return new DeliveryResult(sub, status, error, gone);
		} catch (IOException | GeneralSecurityException | SQLException | IllegalArgumentException x) {
			LOG.debug("Failed to send a push notification to subscription {}", sub.id(), x);
			return new DeliveryResult(sub, 0, x.toString(), false);
		} catch (InterruptedException x) {
			Thread.currentThread().interrupt();
			return new DeliveryResult(sub, 0, x.toString(), false);
		}
	}

	/**
	 * The contact the push services see in the VAPID token. Apple refuses tokens without a valid {@code mailto:} or
	 * {@code https:} subject.
	 */
	private static String vapidSubject() {
		if (StringUtils.isNotEmpty(Config.serverAdminEmail))
			return "mailto:" + Config.serverAdminEmail;
		return "https://" + Config.domain;
	}
}
