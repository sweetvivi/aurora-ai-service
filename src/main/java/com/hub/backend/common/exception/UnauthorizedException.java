package com.hub.backend.common.exception;

/**
 * Raised when a request carries no valid credentials. Mapped to HTTP 401.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
