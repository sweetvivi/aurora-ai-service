package com.hub.backend.infra.llm.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmUsage;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/**
 * Translates between the provider independent command and one supplier's wire format.
 *
 * <p>Each implementation owns the whole request: URL, authentication headers and body. That keeps
 * the differences in one place instead of scattering {@code if (protocol == …)} branches through the
 * client.</p>
 */
public interface ChatProtocolAdapter {

    ChatProtocol protocol();

    /** @return whether this protocol can stream; when false the client refuses streaming clearly */
    boolean supportsStreaming();

    /**
     * @return whether this wire format can express tool calls at all. When {@code false} the
     *         command's {@code tools} are dropped by the adapter and the model simply answers
     *         without invoking anything, rather than failing the turn.
     */
    default boolean supportsTools() {
        return false;
    }

    /**
     * @param stream whether the caller wants server sent events
     */
    HttpRequest build(LlmChatCommand command, ProviderCredentials credentials, LlmProperties properties,
                      ObjectMapper objectMapper, boolean stream);

    /** @return the request body in this protocol's shape, as JSON */
    String body(LlmChatCommand command, ObjectMapper objectMapper, boolean stream);

    LlmChatCompletion parseCompletion(String body, ObjectMapper objectMapper);

    /**
     * @return the chunk carried by one stream line, or {@code null} when the line carries none
     *         (comments, keep-alives, protocol markers)
     */
    LlmStreamChunk parseStreamChunk(String line, ObjectMapper objectMapper);

    // ---------------------------------------------------------------- shared helpers

    /** Joins a base URL with a relative path, tolerating either side's slashes. */
    default String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (path == null || path.isBlank()) {
            return base;
        }
        String suffix = path.strip();
        return suffix.startsWith("/") ? base + suffix : base + "/" + suffix;
    }

    /** @return the API key to use: the supplier's own, else the configured default */
    default String apiKeyOf(ProviderCredentials credentials, String configured) {
        return credentials != null && credentials.hasApiKey() ? credentials.apiKey() : configured;
    }

    default String baseUrlOf(ProviderCredentials credentials, String configured) {
        return credentials != null && credentials.hasBaseUrl() ? credentials.baseUrl() : configured;
    }

    default HttpRequest.Builder builder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
    }

    default HttpRequest post(HttpRequest.Builder builder, String body) {
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    /** Usage with only the fields a protocol actually reports filled in. */
    default LlmUsage usage(Integer prompt, Integer completion) {
        int promptTokens = prompt == null ? 0 : prompt;
        int completionTokens = completion == null ? 0 : completion;
        return new LlmUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
