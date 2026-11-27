package com.hub.backend.common.exception;

/**
 * Raised when an attachment exceeds the configured size limit. Mapped to HTTP 413.
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
