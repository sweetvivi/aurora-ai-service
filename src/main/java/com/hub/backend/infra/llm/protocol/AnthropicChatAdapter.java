package com.hub.backend.infra.llm.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmUsage;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API.
 *
 * <p>Three things differ from the OpenAI shape and each is handled explicitly:</p>
 * <ul>
 *   <li>the system instruction is a top level field, not a message — Anthropic rejects a
 *       {@code system} entry inside {@code messages};</li>
 *   <li>{@code max_tokens} is required, so a default is supplied when the caller omits it;</li>
 *   <li>streaming frames are typed ({@code content_block_delta}, {@code message_delta}, …) rather
 *       than using an OpenAI {@code choices[]} envelope.</li>
 * </ul>
 */
public class AnthropicChatAdapter implements ChatProtocolAdapter {

    private static final String DEFAULT_PATH = "/v1/messages";

    /** Anthropic's own stable API version string; used when the provider does not pin one. */
    private static final String DEFAULT_API_VERSION = "2023-06-01";

    /** Messages API requires max_tokens; this is the usual starting point for a chat turn. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    @Override
    public ChatProtocol protocol() {
        return ChatProtocol.ANTHROPIC;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public HttpRequest build(LlmChatCommand command, ProviderCredentials credentials, LlmProperties properties,
                             ObjectMapper objectMapper, boolean stream) {
        String apiKey = apiKeyOf(credentials, properties.getApiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmInvocationException(
                    "Anthropic supplier has no api key; store one on the provider or set app.llm.api-key");
        }
        if (command.model() == null || command.model().isBlank()) {
            throw new LlmInvocationException("Model key must not be blank");
        }

        String baseUrl = baseUrlOf(credentials, properties.resolvedBaseUrl());
        String path = credentials != null && credentials.chatPath() != null && !credentials.chatPath().isBlank()
                ? credentials.chatPath()
                : DEFAULT_PATH;

        HttpRequest.Builder builder = builder(join(baseUrl, path))
                .header("x-api-key", apiKey)
                .header("anthropic-version", credentials != null && credentials.hasApiVersion()
                        ? credentials.apiVersion() : DEFAULT_API_VERSION)
                .header("Accept", stream ? "text/event-stream" : "application/json");

        return post(builder, body(command, objectMapper, stream));
    }

    @Override
    public String body(LlmChatCommand command, ObjectMapper objectMapper, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("max_tokens", command.maxTokens() == null ? DEFAULT_MAX_TOKENS : command.maxTokens());

        List<Map<String, Object>> messages = new ArrayList<>();
        StringBuilder system = new StringBuilder();
        for (LlmChatMessage message : command.messages()) {
            if (LlmChatMessage.ROLE_SYSTEM.equals(message.role())) {
                // Anthropic takes system content out of band, so collect it instead of emitting a
                // message entry it would reject.
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(message.content() == null ? "" : message.content());
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role());
            if (message.images().isEmpty()) {
                entry.put("content", message.content() == null ? "" : message.content());
            } else {
                List<Map<String, Object>> parts = new ArrayList<>();
                if (message.content() != null && !message.content().isBlank()) {
                    parts.add(Map.of("type", "text", "text", message.content()));
                }
                for (var image : message.images()) {
                    parts.add(Map.of(
                            "type", "image",
                            "source", Map.of(
                                    "type", "base64",
                                    "media_type", image.mimeType(),
                                    "data", Base64.getEncoder().encodeToString(image.content()))));
                }
                entry.put("content", parts);
            }
            messages.add(entry);
        }
        if (!system.isEmpty()) {
            payload.put("system", system.toString());
        }
        payload.put("messages", messages);
        if (command.temperature() != null) {
            payload.put("temperature", command.temperature());
        }
        payload.put("stream", stream);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to serialise the Anthropic request", exception);
        }
    }

    @Override
    public LlmChatCompletion parseCompletion(String body, ObjectMapper objectMapper) {
        JsonNode root = read(body, objectMapper);

        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                String piece = OpenAiChatAdapter.textOrNull(block.path("text"));
                if (piece != null) {
                    text.append(piece);
                }
            }
        }

        JsonNode usage = root.path("usage");
        return new LlmChatCompletion(
                OpenAiChatAdapter.textOrNull(root.path("id")),
                OpenAiChatAdapter.textOrNull(root.path("model")),
                text.isEmpty() ? null : text.toString(),
                OpenAiChatAdapter.textOrNull(root.path("stop_reason")),
                usage.isMissingNode() || usage.isNull()
                        ? LlmUsage.empty()
                        : usage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0)));
    }

    @Override
    public LlmStreamChunk parseStreamChunk(String line, ObjectMapper objectMapper) {
        String payload = dataPayload(line);
        if (payload == null) {
            return null;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse Anthropic stream frame", exception);
        }

        String type = root.path("type").asText("");
        return switch (type) {
            case "message_start" -> new LlmStreamChunk(
                    null,
                    null,
                    null,
                    null,
                    false,
                    usageOf(root.path("message").path("usage")));
            case "content_block_delta" -> {
                JsonNode delta = root.path("delta");
                yield new LlmStreamChunk(null, OpenAiChatAdapter.textOrNull(delta.path("text")),
                        OpenAiChatAdapter.textOrNull(delta.path("thinking")), null, false);
            }
            case "message_delta" -> new LlmStreamChunk(null, null, null,
                    OpenAiChatAdapter.textOrNull(root.path("delta").path("stop_reason")),
                    false,
                    usageOf(root.path("usage")));
            case "message_stop" -> LlmStreamChunk.done(null);
            // message_start, content_block_start/stop, ping and anything new carry no visible text
            default -> null;
        };
    }

    private LlmUsage usageOf(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return usage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
    }

    /**
     * @return the JSON of a {@code data:} line, or {@code null} for other lines ({@code event:},
     *         comments, blanks)
     */
    private static String dataPayload(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.strip();
        if (!trimmed.regionMatches(true, 0, "data:", 0, "data:".length())) {
            return null;
        }
        String payload = trimmed.substring("data:".length()).strip();
        return payload.isEmpty() ? null : payload;
    }

    private static JsonNode read(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            throw new LlmInvocationException("Anthropic returned an empty response body");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse the Anthropic response", exception);
        }
    }
}
