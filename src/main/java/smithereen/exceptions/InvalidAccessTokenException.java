package smithereen.exceptions;

/**
 * Thrown when an API access token can not be used: it is malformed, unknown, expired, or belongs to an account that
 * is no longer active. The message is safe to show to the caller.
 */
public class InvalidAccessTokenException extends RuntimeException {
	public InvalidAccessTokenException(String message) {
		super(message);
	}
}
