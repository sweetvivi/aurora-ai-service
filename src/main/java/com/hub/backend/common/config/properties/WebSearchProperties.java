package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Legacy Bocha/Tavily settings for the deprecated {@code WebSearchTool} bean.
 *
 * <p>Chat and Agent networking use upstream built-in {@code tools:[{type:"web_search"}]}
 * gated by {@code llm_models.supports_web_search}. Do not configure {@code WEB_SEARCH_*} —
 * this bean stays unbound (empty defaults) so the old function-tool path stays off.</p>
 */
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {

    /**
     * Search vendor. Supported: {@code bocha} (default, Chinese web), {@code tavily}.
     */
    private String provider = "bocha";

    /** API key for the selected provider. Empty disables real search. */
    private String apiKey = "";

    /**
     * Vendor endpoint. Required when search is enabled; there is no baked-in provider URL.
     */
    private String baseUrl = "";

    /** How many hits to return to the model (clamped per provider). */
    private int maxResults = 8;

    private Duration connectTimeout = Duration.ofSeconds(10);

    private Duration readTimeout = Duration.ofSeconds(30);

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }
}
