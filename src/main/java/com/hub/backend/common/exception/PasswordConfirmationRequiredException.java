package com.hub.backend.common.exception;

/**
 * Raised when an operation needs the account password the server does not currently hold.
 * Mapped to HTTP 428, so a client can ask for the password once and retry the same request.
 *
 * <p>The plaintext password of an active session lives in memory only
 * ({@link com.hub.backend.common.security.SessionPasswordVault}). A restart, an expired vault entry
 * or a signed-in-before-that-change session leaves it empty while the access token is still valid.
 * The session itself is not broken, so this must not be reported as 401 or 503: 401 would sign the
 * user out, and 503 would read as "try again later" when retrying can never help.</p>
 */
public class PasswordConfirmationRequiredException extends RuntimeException {

    public PasswordConfirmationRequiredException(String message) {
        super(message);
    }
}
