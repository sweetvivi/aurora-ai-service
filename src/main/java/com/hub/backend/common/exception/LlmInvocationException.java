package com.hub.backend.common.exception;

/**
 * Raised when the upstream model gateway cannot be reached or answers with an error.
 *
 * <p>The message never contains the API key; only the HTTP status and a truncated body of the
 * upstream error are propagated.</p>
 *
 * <p><b>Why the status is a field and not just text.</b> The message carries the status inside a
 * sentence ({@code "Image gateway returned HTTP 400: {...}"}), which is fine for logs but useless
 * for deciding what to send the client: a 400 "content policy" answer and a 502 "gateway is down"
 * are both "an upstream error" here, yet only one of them is the user's fault and should be
 * reported as such. Wrapping clients therefore record the status (and the upstream
 * {@code error.code}) next to the message, and {@code GlobalExceptionHandler} reads them — without
 * them it has no choice but to answer 502 for everything, which is what used to happen to every
 * content-policy refusal.</p>
 */
public class LlmInvocationException extends RuntimeException {

    /** Upstream HTTP status, or null when the failure happened before/without a response. */
    private final Integer upstreamStatus;

    /** Upstream {@code error.code} when the gateway supplied one, e.g. {@code upstream_text_reply}. */
    private final String upstreamCode;

    public LlmInvocationException(String message) {
        this(message, null, null, null);
    }

    public LlmInvocationException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public LlmInvocationException(String message, Integer upstreamStatus, String upstreamCode) {
        this(message, null, upstreamStatus, upstreamCode);
    }

    public LlmInvocationException(
            String message, Throwable cause, Integer upstreamStatus, String upstreamCode) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getUpstreamCode() {
        return upstreamCode;
    }
}
