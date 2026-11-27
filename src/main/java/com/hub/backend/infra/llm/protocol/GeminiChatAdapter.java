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
 * Google Gemini {@code generateContent}.
 *
 * <p>Gemini maps onto the same conversation in a different vocabulary: messages become
 * {@code contents} with roles {@code user}/{@code model}, a system instruction becomes
 * {@code systemInstruction}, and generation knobs live under {@code generationConfig}. Streaming is
 * the same call with {@code :streamGenerateContent?alt=sse}, which returns one JSON document per
 * {@code data:} frame.</p>
 */
public class GeminiChatAdapter implements ChatProtocolAdapter {

    private static final String DEFAULT_BASE_PATH = "/v1beta/models";

    @Override
    public ChatProtocol protocol() {
        return ChatProtocol.GEMINI;
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
                    "Gemini supplier has no api key; store one on the provider or set app.llm.api-key");
        }
        if (command.model() == null || command.model().isBlank()) {
            throw new LlmInvocationException("Model key must not be blank");
        }

        String baseUrl = baseUrlOf(credentials, properties.resolvedBaseUrl());
        // A provider may override the whole prefix; otherwise the documented path is used.
        String prefix = credentials != null && credentials.chatPath() != null && !credentials.chatPath().isBlank()
                ? credentials.chatPath()
                : DEFAULT_BASE_PATH;
        String method = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";

        // The key travels in a header rather than the query string so it stays out of access logs.
        HttpRequest.Builder builder = builder(join(baseUrl, prefix + "/" + command.model() + method))
                .header("x-goog-api-key", apiKey)
                .header("Accept", stream ? "text/event-stream" : "application/json");

        return post(builder, body(command, objectMapper, stream));
    }

    @Override
    public String body(LlmChatCommand command, ObjectMapper objectMapper, boolean stream) {
        List<Map<String, Object>> contents = new ArrayList<>();
        StringBuilder system = new StringBuilder();

        for (LlmChatMessage message : command.messages()) {
            if (LlmChatMessage.ROLE_SYSTEM.equals(message.role())) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(message.content() == null ? "" : message.content());
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", LlmChatMessage.ROLE_ASSISTANT.equals(message.role()) ? "model" : "user");
            List<Map<String, Object>> parts = new ArrayList<>();
            if (message.content() != null && !message.content().isBlank()) {
                parts.add(Map.of("text", message.content()));
            }
            for (var image : message.images()) {
                parts.add(Map.of("inlineData", Map.of(
                        "mimeType", image.mimeType(),
                        "data", Base64.getEncoder().encodeToString(image.content()))));
            }
            if (parts.isEmpty()) {
                parts.add(Map.of("text", ""));
            }
            entry.put("parts", parts);
            contents.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!system.isEmpty()) {
            payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system.toString()))));
        }
        payload.put("contents", contents);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (command.temperature() != null) {
            generationConfig.put("temperature", command.temperature());
        }
        if (command.maxTokens() != null) {
            generationConfig.put("maxOutputTokens", command.maxTokens());
        }
        if (!generationConfig.isEmpty()) {
            payload.put("generationConfig", generationConfig);
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to serialise the Gemini request", exception);
        }
    }

    @Override
    public LlmChatCompletion parseCompletion(String body, ObjectMapper objectMapper) {
        JsonNode root = read(body, objectMapper);
        JsonNode candidate = root.path("candidates").path(0);
        return new LlmChatCompletion(
                OpenAiChatAdapter.textOrNull(root.path("responseId")),
                OpenAiChatAdapter.textOrNull(root.path("modelVersion")),
                textOf(candidate),
                OpenAiChatAdapter.textOrNull(candidate.path("finishReason")),
                usageOf(root));
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
            throw new LlmInvocationException("Unable to parse Gemini stream frame", exception);
        }

        // The last frame carries usageMetadata only; content-bearing frames are appended to.
        return new LlmStreamChunk(
                OpenAiChatAdapter.textOrNull(root.path("responseId")),
                textOf(root.path("candidates").path(0)),
                null,
                OpenAiChatAdapter.textOrNull(root.path("candidates").path(0).path("finishReason")),
                false,
                usageOf(root));
    }

    private static String textOf(JsonNode candidate) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            String piece = OpenAiChatAdapter.textOrNull(part.path("text"));
            if (piece != null) {
                text.append(piece);
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static LlmUsage usageOf(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        if (usage.isMissingNode() || usage.isNull()) {
            return LlmUsage.empty();
        }
        int prompt = usage.path("promptTokenCount").asInt(0);
        int completion = usage.path("candidatesTokenCount").asInt(0);
        int total = usage.path("totalTokenCount").asInt(prompt + completion);
        return new LlmUsage(prompt, completion, total);
    }

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
            throw new LlmInvocationException("Gemini returned an empty response body");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse the Gemini response", exception);
        }
    }
}
