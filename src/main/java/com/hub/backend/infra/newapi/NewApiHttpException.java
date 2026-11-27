package com.hub.backend.infra.newapi;

import com.hub.backend.common.exception.LlmInvocationException;

/**
 * A non-2xx response from New API.
 *
 * <p>The status is retained so credential recovery can distinguish a revoked token from a
 * transient upstream failure.</p>
 */
public class NewApiHttpException extends LlmInvocationException {

    private final int statusCode;

    public NewApiHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isAuthenticationFailure() {
        return statusCode == 401 || statusCode == 403;
    }
}
