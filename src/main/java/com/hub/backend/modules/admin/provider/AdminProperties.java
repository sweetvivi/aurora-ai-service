package com.hub.backend.modules.admin.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Management-plane key until proper admin RBAC lands.
 */
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    /**
     * Retained for configuration compatibility. The guard always requires {@link #apiKey}; this
     * flag can no longer disable authentication.
     *
     * @deprecated the admin API key is mandatory and cannot be switched off
     */
    @Deprecated(forRemoval = false)
    private boolean requireApiKey = true;

    private String apiKey = "";

    @Deprecated(forRemoval = false)
    public boolean isRequireApiKey() {
        return requireApiKey;
    }

    @Deprecated(forRemoval = false)
    public void setRequireApiKey(boolean requireApiKey) {
        this.requireApiKey = requireApiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
