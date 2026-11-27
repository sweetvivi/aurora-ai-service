package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;

/**
 * Shared outbound HTTP client used by the {@code infra} adapters.
 *
 * <p>HTTP/1.1 is pinned on purpose: several OpenAI-compatible relays reject the
 * HTTP/2 upgrade or close the stream prematurely.</p>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    @Primary
    public HttpClient llmHttpClient(LlmProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    @Bean
    public HttpClient imageDownloadHttpClient(LlmProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }
}
