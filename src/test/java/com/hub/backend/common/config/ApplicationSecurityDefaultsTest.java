package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.AuthProperties;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.config.properties.PptProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationSecurityDefaultsTest {

    @Test
    void jwtSecretHasNoRepositoryDefault() throws IOException {
        PropertySource<?> application = applicationProperties();

        assertEquals("${AUTH_JWT_SECRET:}", application.getProperty("app.auth.jwt-secret"));
        assertEquals("", new AuthProperties().getJwtSecret());
    }

    @Test
    void upstreamModelCredentialHasNoRepositoryDefault() throws IOException {
        PropertySource<?> application = applicationProperties();

        assertNull(application.getProperty("app.llm.api-key"));
        assertEquals("", new LlmProperties().getApiKey());
    }

    @Test
    void serviceAddressesHaveNoRepositoryDefault() throws IOException {
        PropertySource<?> application = applicationProperties();

        // The relay address is derived from the New API origin rather than committed: no host is
        // hardcoded, and an explicit LLM_BASE_URL still wins when model traffic uses another origin.
        assertEquals("${LLM_BASE_URL:${NEW_API_BASE_URL:}/v1}",
                application.getProperty("app.llm.base-url"));
        assertEquals("", new LlmProperties().getBaseUrl());

        assertEquals("${NEW_API_BASE_URL:}", application.getProperty("app.new-api.base-url"));
        assertEquals("", new NewApiProperties().getBaseUrl());

        assertEquals("${PPT_MASTER_BASE_URL:}", application.getProperty("app.ppt.ppt-master.base-url"));
        assertEquals("${PPT_MASTER_REPO:}", application.getProperty("app.ppt.ppt-master.repo-root"));
        assertEquals("", new PptProperties().getPptMaster().getBaseUrl());
        assertEquals("", new PptProperties().getPptMaster().getRepoRoot());

        // Chat networking uses upstream built-in web_search; no WEB_SEARCH_* in application.yml.
        assertNull(application.getProperty("app.web-search.base-url"));
        assertNull(application.getProperty("app.web-search.api-key"));
    }

    @Test
    void relayAddressIsDerivedFromTheNewApiOrigin() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(applicationProperties());
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

        // Neither variable set: the suffix alone is left, which resolvedBaseUrl() rejects at startup
        // rather than sending a relative URL upstream.
        assertEquals("/v1", resolver.getProperty("app.llm.base-url"));

        sources.addFirst(new MapPropertySource("deployment", Map.of(
                "NEW_API_BASE_URL", "http://new-api:3000")));
        assertEquals("http://new-api:3000/v1", resolver.getProperty("app.llm.base-url"));

        // An explicit relay origin still wins, for deployments whose model traffic leaves elsewhere.
        sources.addFirst(new MapPropertySource("override", Map.of(
                "LLM_BASE_URL", "https://relay.example.com/v1")));
        assertEquals("https://relay.example.com/v1", resolver.getProperty("app.llm.base-url"));
    }

    @Test
    void relativeRelayBaseUrlIsRejected() {
        LlmProperties properties = new LlmProperties();
        // What a missing NEW_API_BASE_URL produces once /v1 is appended.
        properties.setBaseUrl("/v1");

        assertThrows(IllegalStateException.class, properties::resolvedBaseUrl);
    }

    private static PropertySource<?> applicationProperties() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertEquals(1, sources.size());
        return sources.get(0);
    }
}
