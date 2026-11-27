package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Site resolution and default runtime switches.
 *
 * <p>Bound from {@code app.site.*}. These values are the fallback layer: database settings of the
 * resolved site override them, so a site can be re-configured without a redeploy.</p>
 */
@ConfigurationProperties(prefix = "app.site")
public class AppProperties {

    /** Site key used when a request carries neither {@code app-name} nor a known domain. */
    private String defaultKey = "default";

    /** Request header carrying the requested site key. */
    private String siteHeader = "app-name";

    /** Whether an unknown {@code Host} may be mapped through {@code app_sites.domain}. */
    private boolean domainFallback = true;

    /** Feature switches applied before the site specific overrides. */
    private Map<String, Boolean> defaultFeatures = defaultFeatures();

    private static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("chat", true);
        features.put("stream", true);
        features.put("attachment", false);
        features.put("share", false);
        features.put("points", false);
        return features;
    }

    public String getDefaultKey() {
        return defaultKey;
    }

    public void setDefaultKey(String defaultKey) {
        this.defaultKey = defaultKey;
    }

    public String getSiteHeader() {
        return siteHeader;
    }

    public void setSiteHeader(String siteHeader) {
        this.siteHeader = siteHeader;
    }

    public boolean isDomainFallback() {
        return domainFallback;
    }

    public void setDomainFallback(boolean domainFallback) {
        this.domainFallback = domainFallback;
    }

    public Map<String, Boolean> getDefaultFeatures() {
        return defaultFeatures;
    }

    public void setDefaultFeatures(Map<String, Boolean> defaultFeatures) {
        this.defaultFeatures = defaultFeatures;
    }
}
