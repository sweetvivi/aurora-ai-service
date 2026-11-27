package com.hub.backend.infra.newapi;

import com.hub.backend.common.exception.LlmInvocationException;

/**
 * New API answered HTTP 200 but refused the operation in the body ({@code success: false}).
 *
 * <p>It deserves its own type because that refusal carries a business meaning — bad credentials, a
 * duplicate username, a banned account — while an unparseable body or a 5xx carries none. Collapsing
 * both into "the upstream failed" is how a wrong password stops being recognisable as a wrong
 * password: {@code /api/user/login} answers 200 for bad credentials, so without this distinction a
 * rejected password looks exactly like a gateway outage.</p>
 */
public class NewApiRejectedException extends LlmInvocationException {

    public NewApiRejectedException(String message) {
        super(message);
    }
}
