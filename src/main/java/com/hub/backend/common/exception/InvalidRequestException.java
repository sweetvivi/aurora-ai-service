package com.hub.backend.common.exception;

/**
 * Raised when a request is syntactically valid but semantically unusable, for example a chat
 * request without any user turn. Mapped to HTTP 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
