package com.hub.backend.common.exception;

/**
 * Raised when an optional execution backend is not configured or unavailable.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
