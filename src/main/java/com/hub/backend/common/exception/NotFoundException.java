package com.hub.backend.common.exception;

/**
 * Raised when a referenced business resource does not exist for the current tenant or user.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
