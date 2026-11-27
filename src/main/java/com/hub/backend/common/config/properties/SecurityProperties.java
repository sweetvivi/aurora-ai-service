package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Legacy identity fallback settings.
 *
 * <p>Bound from {@code app.security.*}.</p>
 *
 * <p>HTTP request APIs resolve identity exclusively from Bearer access tokens. A development-only
 * header may still select an account through the legacy non-request resolver when
 * {@code app.security.trust-user-header=true}; that header is client supplied and is <em>not</em>
 * an authorisation mechanism.</p>
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** Whether a client supplied user header may select the account. */
    private boolean trustUserHeader = false;

    /** Request header carrying the account id while {@link #trustUserHeader} is enabled. */
    private String userHeader = "X-User-Id";

    /** Account that owns resources created without an explicit identity. */
    private String defaultUsername = "demo";

    /**
     * Whether {@code X-Forwarded-For} may be used as the client address.
     *
     * <p>Off by default on purpose: without a trusted proxy in front, the header is client supplied,
     * so trusting it would let anyone bypass an address based rate limit by rotating the value.
     * Enable it only when the service really sits behind a proxy that overwrites the header.</p>
     */
    private boolean trustForwardedFor = false;

    public boolean isTrustUserHeader() {
        return trustUserHeader;
    }

    public void setTrustUserHeader(boolean trustUserHeader) {
        this.trustUserHeader = trustUserHeader;
    }

    public String getUserHeader() {
        return userHeader;
    }

    public void setUserHeader(String userHeader) {
        this.userHeader = userHeader;
    }

    public String getDefaultUsername() {
        return defaultUsername;
    }

    public void setDefaultUsername(String defaultUsername) {
        this.defaultUsername = defaultUsername;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }
}
