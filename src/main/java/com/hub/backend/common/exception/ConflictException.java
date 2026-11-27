package com.hub.backend.common.exception;

/**
 * Raised when a create would collide with an existing resource. Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
