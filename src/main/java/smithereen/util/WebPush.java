package smithereen.util;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cryptography of Web Push, done with the JDK alone: payload encryption (RFC 8291, the {@code aes128gcm} content
 * encoding of RFC 8188) and the VAPID authorization header (RFC 8292). All keys are P-256; public keys are passed
 * around in the uncompressed 65-byte form browsers use ({@code 0x04 || X || Y}).
 */
public class WebPush {
	private static final int RECORD_SIZE = 4096;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final ECParameterSpec P256;

	static {
		try {
			AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
			params.init(new ECGenParameterSpec("secp256r1"));
			P256 = params.getParameterSpec(ECParameterSpec.class);
		} catch (GeneralSecurityException x) {
			throw new IllegalStateException(x);
		}
	}

	public static KeyPair generateKeyPair() {
		try {
			KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
			gen.initialize(P256);
			return gen.generateKeyPair();
		} catch (GeneralSecurityException x) {
			throw new IllegalStateException(x);
		}
	}

	public static byte[] encodePublicKey(ECPublicKey key) {
		byte[] out = new byte[65];
		out[0] = 4;
		writeUnsigned32(key.getW().getAffineX(), out, 1);
		writeUnsigned32(key.getW().getAffineY(), out, 33);
		return out;
	}

	public static ECPublicKey decodePublicKey(byte[] raw) throws GeneralSecurityException {
		if (raw.length != 65 || raw[0] != 4)
			throw new GeneralSecurityException("Not an uncompressed P-256 point");
		BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
		BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
		// The key factory accepts any coordinates, so check y² = x³ + ax + b (mod p) here
		BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
		BigInteger rhs = x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
		if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !y.modPow(BigInteger.TWO, p).equals(rhs))
			throw new GeneralSecurityException("The point is not on the P-256 curve");
		return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
	}

	public static byte[] encodePrivateKey(ECPrivateKey key) {
		byte[] out = new byte[32];
		writeUnsigned32(key.getS(), out, 0);
		return out;
	}

	public static ECPrivateKey decodePrivateKey(byte[] raw) throws GeneralSecurityException {
		return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
	}

	/**
	 * Encrypts a push message for one subscription with a fresh ephemeral key and salt.
	 *
	 * @param uaPublic   the subscription's {@code p256dh} key
	 * @param authSecret the subscription's {@code auth} secret
	 * @return the request body, to be sent with {@code Content-Encoding: aes128gcm}
	 */
	public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		return encrypt(payload, uaPublic, authSecret, generateKeyPair(), salt);
	}

	static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) throws GeneralSecurityException {
		if (payload.length + 1 + 16 > RECORD_SIZE - 86)
			throw new IllegalArgumentException("Payload is too long for a single record");
		byte[] asPublic = encodePublicKey((ECPublicKey) asKeys.getPublic());

		KeyAgreement ka = KeyAgreement.getInstance("ECDH");
		ka.init(asKeys.getPrivate());
		ka.doPhase(decodePublicKey(uaPublic), true);
		byte[] ecdhSecret = ka.generateSecret();

		// HKDF with a single output block, as every length here is at most 32 bytes
		byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
		byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
		byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
		byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
		// 0x02 marks the last (and only) record, with no padding after it
		byte[] ciphertext = cipher.doFinal(concat(payload, new byte[]{2}));

		ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length);
		body.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ciphertext);
		return body.array();
	}

	/**
	 * Builds the {@code Authorization} header value that identifies this server to the push service.
	 *
	 * @param endpoint the subscription endpoint; the token is only valid for its origin
	 * @param subject  a {@code mailto:} or {@code https:} URI the push service can use to contact the server operator
	 */
	public static String vapidAuthorization(URI endpoint, ECPrivateKey privateKey, byte[] publicKey, String subject, Instant expiresAt) throws GeneralSecurityException {
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
		String header = b64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
		String claims = b64.encodeToString(new JsonObjectBuilder()
				.add("aud", audience)
				.add("exp", expiresAt.getEpochSecond())
				.add("sub", subject)
				.build()
				.toString()
				.getBytes(StandardCharsets.UTF_8));
		Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
		sig.initSign(privateKey);
		sig.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
		return "vapid t=" + header + "." + claims + "." + b64.encodeToString(sig.sign()) + ", k=" + b64.encodeToString(publicKey);
	}

	private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(salt, "HmacSHA256"));
		byte[] prk = mac.doFinal(ikm);
		mac.init(new SecretKeySpec(prk, "HmacSHA256"));
		mac.update(info);
		mac.update((byte) 1);
		return Arrays.copyOf(mac.doFinal(), length);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

	private static void writeUnsigned32(BigInteger value, byte[] out, int offset) {
		byte[] bytes = value.toByteArray();
		// toByteArray() may add a leading zero for the sign or return fewer than 32 bytes
		int len = Math.min(bytes.length, 32);
		System.arraycopy(bytes, bytes.length - len, out, offset + 32 - len, len);
	}
}
