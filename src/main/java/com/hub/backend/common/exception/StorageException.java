package com.hub.backend.common.exception;

/**
 * Raised when the blob store cannot fulfil a request. Mapped to HTTP 500; the message never
 * contains storage credentials or absolute server paths.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
