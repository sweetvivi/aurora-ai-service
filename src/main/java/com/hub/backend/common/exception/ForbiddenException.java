package com.hub.backend.common.exception;

/**
 * Raised when credentials are valid but the account may not perform the action. Mapped to HTTP 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
