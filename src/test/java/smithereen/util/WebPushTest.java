package smithereen.util;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;

public class WebPushTest {
	private static byte[] b64(String s) {
		return Base64.getUrlDecoder().decode(s);
	}

	/**
	 * The example from RFC 8291 section 5, with the sender key and salt from its appendix A.
	 */
	@Test
	public void encryptsTheRfcExample() throws GeneralSecurityException {
		ECPublicKey asPublic = WebPush.decodePublicKey(b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"));
		ECPrivateKey asPrivate = WebPush.decodePrivateKey(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
		byte[] body = WebPush.encrypt(
				"When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
				b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
				b64("BTBZMqHH6r4Tts7J_aSIgg"),
				new KeyPair(asPublic, asPrivate),
				b64("DGv6ra1nlYgDCS1FRnbzlw"));
		assertArrayEquals(b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
				+ "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
				+ "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"), body);
	}

	@Test
	public void keysSurviveEncoding() throws GeneralSecurityException {
		KeyPair keys = WebPush.generateKeyPair();
		byte[] pub = WebPush.encodePublicKey((ECPublicKey) keys.getPublic());
		assertEquals(65, pub.length);
		assertEquals(keys.getPublic(), WebPush.decodePublicKey(pub));
		ECPrivateKey priv = (ECPrivateKey) keys.getPrivate();
		assertEquals(priv.getS(), WebPush.decodePrivateKey(WebPush.encodePrivateKey(priv)).getS());
	}

	@Test
	public void rejectsPointsOffTheCurve() {
		byte[] raw = new byte[65];
		raw[0] = 4;
		raw[64] = 1;
		assertThrows(GeneralSecurityException.class, () -> WebPush.decodePublicKey(raw));
		assertThrows(GeneralSecurityException.class, () -> WebPush.decodePublicKey(new byte[33]));
	}

	@Test
	public void vapidTokenIsSignedForTheEndpointOrigin() throws Exception {
		KeyPair keys = WebPush.generateKeyPair();
		byte[] pub = WebPush.encodePublicKey((ECPublicKey) keys.getPublic());
		String header = WebPush.vapidAuthorization(URI.create("https://web.push.apple.com/QGuQyavXutnMH?x=1"), (ECPrivateKey) keys.getPrivate(), pub,
				"mailto:admin@example.com", Instant.ofEpochSecond(1700000000));

		assertTrue(header.startsWith("vapid t="));
		String[] parts = header.substring("vapid t=".length()).split(", k=");
		assertArrayEquals(pub, b64(parts[1]));
		String[] jwt = parts[0].split("\\.");
		assertEquals(3, jwt.length);
		JsonObject claims = JsonParser.parseString(new String(b64(jwt[1]), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("https://web.push.apple.com", claims.get("aud").getAsString());
		assertEquals(1700000000L, claims.get("exp").getAsLong());
		assertEquals("mailto:admin@example.com", claims.get("sub").getAsString());

		Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
		sig.initVerify(keys.getPublic());
		sig.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
		assertTrue(sig.verify(b64(jwt[2])));
	}
}
