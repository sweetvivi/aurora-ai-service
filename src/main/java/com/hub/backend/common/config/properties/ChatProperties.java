package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Runtime behaviour of the {@code /api/v1/chat} endpoints.
 *
 * <p>Bound from {@code app.chat.*}.</p>
 */
@ConfigurationProperties(prefix = "app.chat")
public class ChatProperties {

    /** When true the request and the assistant reply are persisted to the conversation tables. */
    private boolean persistenceEnabled = true;

    /** How long an SSE connection may stay open. */
    private Duration streamTimeout = Duration.ofMinutes(5);

    /**
     * How long a finished generation stays cancellable. Keeping the id around briefly makes the
     * stop button idempotent when it is pressed just as the answer completes.
     */
    private Duration requestRetention = Duration.ofMinutes(2);

    /** Upper bound on tracked generations, so abandoned ids cannot grow memory without limit. */
    private int maxTrackedRequests = 10_000;

    /** Maximum title length stored on auto-created conversations. */
    private int titleMaxLength = 60;

    /**
     * Whether the server should draw a picture on its own when the model answered with text only.
     *
     * <p>Off by default. Image generation normally goes through the {@code generate_image} tool the
     * model calls itself; this switch exists only for relays that do not pass {@code tools} through,
     * where the model never gets the chance to call it. Turning it on trades correctness for
     * coverage: the intent is guessed from a regex, so "帮我写一段生图描述" style questions can be
     * misread as draw requests.</p>
     */
    private boolean imageIntentFallback = false;

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public void setPersistenceEnabled(boolean persistenceEnabled) {
        this.persistenceEnabled = persistenceEnabled;
    }

    public Duration getStreamTimeout() {
        return streamTimeout;
    }

    public Duration getRequestRetention() {
        return requestRetention;
    }

    public void setRequestRetention(Duration requestRetention) {
        this.requestRetention = requestRetention;
    }

    public int getMaxTrackedRequests() {
        return maxTrackedRequests;
    }

    public void setMaxTrackedRequests(int maxTrackedRequests) {
        this.maxTrackedRequests = maxTrackedRequests;
    }

    public void setStreamTimeout(Duration streamTimeout) {
        this.streamTimeout = streamTimeout;
    }

    public int getTitleMaxLength() {
        return titleMaxLength;
    }

    public void setTitleMaxLength(int titleMaxLength) {
        this.titleMaxLength = titleMaxLength;
    }

    public boolean isImageIntentFallback() {
        return imageIntentFallback;
    }

    public void setImageIntentFallback(boolean imageIntentFallback) {
        this.imageIntentFallback = imageIntentFallback;
    }
}
