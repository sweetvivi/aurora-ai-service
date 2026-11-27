package com.hub.backend.common.exception;

/**
 * Raised when a client exceeded a rate limit. Mapped to HTTP 429.
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** @return seconds the client should wait, emitted as the {@code Retry-After} header */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
