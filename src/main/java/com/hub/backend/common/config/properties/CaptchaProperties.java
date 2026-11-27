package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Image captcha guarding account creation.
 *
 * <p>Bound from {@code app.captcha.*}.</p>
 *
 * <p>The per address registration limit already caps how many accounts one address may create, but
 * it counts attempts and is per process: a script that spreads across addresses, or that simply
 * retries, still reaches BCrypt. A captcha is the complementary control — it makes each attempt
 * cost a human step regardless of where the request comes from.</p>
 */
@ConfigurationProperties(prefix = "app.captcha")
public class CaptchaProperties {

    /**
     * Whether {@code POST /api/v1/auth/register} requires a captcha.
     *
     * <p>Turning this off also turns the challenge endpoint off, so the frontend falls back to the
     * pre-captcha form. Intended for tests and for local debugging, not for production.</p>
     */
    private boolean enabled = true;

    /** Number of characters in the answer. Four is the usual readability/entropy trade. */
    private int length = 4;

    /** How long an issued challenge stays answerable. */
    private Duration ttl = Duration.ofMinutes(5);

    /** Upper bound on stored challenges, so a flood of {@code GET /auth/captcha} cannot grow memory. */
    private int maxStored = 10_000;

    /**
     * Challenges one address may request per {@link #issueWindow}. Reading a captcha costs a PNG
     * render, so the image endpoint needs its own ceiling; a human needs a handful.
     */
    private int maxIssuesPerIp = 120;

    /** Length of the challenge issuing window. */
    private Duration issueWindow = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxStored() {
        return maxStored;
    }

    public void setMaxStored(int maxStored) {
        this.maxStored = maxStored;
    }

    public int getMaxIssuesPerIp() {
        return maxIssuesPerIp;
    }

    public void setMaxIssuesPerIp(int maxIssuesPerIp) {
        this.maxIssuesPerIp = maxIssuesPerIp;
    }

    public Duration getIssueWindow() {
        return issueWindow;
    }

    public void setIssueWindow(Duration issueWindow) {
        this.issueWindow = issueWindow;
    }
}
