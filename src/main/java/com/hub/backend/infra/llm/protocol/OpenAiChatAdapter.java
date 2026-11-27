package com.hub.backend.infra.llm.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.LlmSseParser;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.ThinkingValueCodec;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.infra.llm.dto.LlmUsage;

import java.net.http.HttpRequest;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers the OpenAI family: {@link ChatProtocol#OPENAI}, {@link ChatProtocol#OPENAI_COMPATIBLE},
 * {@link ChatProtocol#OLLAMA} and {@link ChatProtocol#AZURE_OPENAI}.
 *
 * <p>They differ in three places only — the path, the authentication header, and whether a key is
 * required at all — so they share one adapter rather than four near-identical classes.</p>
 */
public class OpenAiChatAdapter implements ChatProtocolAdapter {

    private static final String DEFAULT_CHAT_PATH = "/chat/completions";

    private final ChatProtocol protocol;

    public OpenAiChatAdapter(ChatProtocol protocol) {
        if (protocol != ChatProtocol.OPENAI && protocol != ChatProtocol.OPENAI_COMPATIBLE
                && protocol != ChatProtocol.OLLAMA && protocol != ChatProtocol.AZURE_OPENAI) {
            throw new IllegalArgumentException("Not an OpenAI-family protocol: " + protocol);
        }
        this.protocol = protocol;
    }

    @Override
    public ChatProtocol protocol() {
        return protocol;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public HttpRequest build(LlmChatCommand command, ProviderCredentials credentials, LlmProperties properties,
                             ObjectMapper objectMapper, boolean stream) {
        String baseUrl = baseUrlOf(credentials, properties.resolvedBaseUrl());
        String apiKey = apiKeyOf(credentials, properties.getApiKey());

        // Ollama runs locally and is commonly left unauthenticated.
        if (protocol != ChatProtocol.OLLAMA && (apiKey == null || apiKey.isBlank())) {
            throw new LlmInvocationException(
                    "%s supplier has no api key; store one on the provider or set app.llm.api-key"
                            .formatted(protocol.label()));
        }
        if (command.model() == null || command.model().isBlank()) {
            throw new LlmInvocationException("Model key must not be blank");
        }

        HttpRequest.Builder builder = builder(url(command, credentials, baseUrl, properties));
        switch (protocol) {
            case AZURE_OPENAI -> builder.header("api-key", apiKey);
            case OLLAMA -> {
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }
            }
            default -> builder.header("Authorization", "Bearer " + apiKey);
        }
        builder.header("Accept", stream ? "text/event-stream" : "application/json");

        return post(builder, body(command, objectMapper, stream));
    }

    private String url(LlmChatCommand command, ProviderCredentials credentials, String baseUrl,
                       LlmProperties properties) {
        if (protocol == ChatProtocol.AZURE_OPENAI) {
            if (credentials == null || !credentials.hasApiVersion()) {
                throw new LlmInvocationException(
                        "Azure OpenAI suppliers need an apiVersion; set it on the provider");
            }
            return join(baseUrl, "/openai/deployments/%s/chat/completions?api-version=%s"
                    .formatted(command.model(), credentials.apiVersion()));
        }

        String path = credentials != null && credentials.chatPath() != null && !credentials.chatPath().isBlank()
                ? credentials.chatPath()
                : (properties.getChatCompletionsPath() == null || properties.getChatCompletionsPath().isBlank()
                        ? DEFAULT_CHAT_PATH
                        : properties.getChatCompletionsPath());
        return join(baseUrl, path);
    }

    @Override
    public String body(LlmChatCommand command, ObjectMapper objectMapper, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("messages", messages(command.messages()));
        payload.put("stream", stream);
        if (stream) {
            payload.put("stream_options", Map.of("include_usage", true));
        }
        if (command.temperature() != null) {
            payload.put("temperature", command.temperature());
        }
        if (command.maxTokens() != null) {
            payload.put("max_tokens", command.maxTokens());
        }
        // Always write the configured field when present: omitting it leaves default-on reasoning
        // models (e.g. DeepSeek-R1) thinking even after the user turned the switch off.
        if (command.thinkingParameter() != null && !command.thinkingParameter().isBlank()) {
            payload.put(
                    command.thinkingParameter(),
                    ThinkingValueCodec.coerce(
                            command.thinking() ? command.thinkingOnValue() : command.thinkingOffValue(),
                            command.thinking()));
        }
        if (command.hasTools()) {
            payload.put("tools", tools(command.tools(), command.builtInTools()));
            // auto: let the model decide whether this turn needs a tool at all
            payload.put("tool_choice", "auto");
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to serialise the model request", exception);
        }
    }

    private static List<Map<String, Object>> messages(List<LlmChatMessage> messages) {
        List<Map<String, Object>> payload = new ArrayList<>(messages.size());
        for (LlmChatMessage message : messages) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role());

            // A tool result answers one call: the gateway matches it by tool_call_id.
            if (message.isToolResult()) {
                entry.put("content", message.content() == null ? "" : message.content());
                entry.put("tool_call_id", message.toolCallId());
                payload.add(entry);
                continue;
            }

            // An assistant turn that requested tools. Its content is usually empty, but the
            // gateway still expects the field, and the calls must be echoed verbatim.
            if (message.hasToolCalls()) {
                entry.put("content", message.content() == null ? "" : message.content());
                List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
                for (LlmToolCall call : message.toolCalls()) {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", call.id());
                    item.put("type", "function");
                    item.put("function", function);
                    calls.add(item);
                }
                entry.put("tool_calls", calls);
                payload.add(entry);
                continue;
            }

            if (message.images().isEmpty()) {
                entry.put("content", message.content() == null ? "" : message.content());
            } else {
                List<Map<String, Object>> parts = new ArrayList<>();
                if (message.content() != null && !message.content().isBlank()) {
                    parts.add(Map.of("type", "text", "text", message.content()));
                }
                for (var image : message.images()) {
                    String dataUrl = "data:%s;base64,%s".formatted(
                            image.mimeType(), Base64.getEncoder().encodeToString(image.content()));
                    parts.add(Map.of(
                            "type", "image_url",
                            "image_url", Map.of("url", dataUrl)));
                }
                entry.put("content", parts);
            }
            payload.add(entry);
        }
        return payload;
    }

    private static List<Map<String, Object>> tools(List<LlmToolDefinition> definitions,
                                                    List<String> builtInTools) {
        List<Map<String, Object>> payload = new ArrayList<>(
                definitions.size() + (builtInTools == null ? 0 : builtInTools.size()));
        for (LlmToolDefinition definition : definitions) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", definition.name());
            if (definition.description() != null && !definition.description().isBlank()) {
                function.put("description", definition.description());
            }
            function.put("parameters", definition.parameters());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function");
            item.put("function", function);
            payload.add(item);
        }
        if (builtInTools != null) {
            for (String builtIn : builtInTools) {
                if (builtIn == null || builtIn.isBlank()) {
                    continue;
                }
                // OpenAI / compatible gateways: {"type":"web_search"} (not a function tool).
                payload.add(Map.of("type", builtIn.strip()));
            }
        }
        return payload;
    }

    @Override
    public LlmChatCompletion parseCompletion(String body, ObjectMapper objectMapper) {
        JsonNode root = read(body, objectMapper);
        JsonNode choice = root.path("choices").path(0);
        JsonNode usageNode = root.path("usage");
        LlmUsage usage = usageNode.isMissingNode() || usageNode.isNull()
                ? LlmUsage.empty()
                : new LlmUsage(
                        usageNode.path("prompt_tokens").asInt(0),
                        usageNode.path("completion_tokens").asInt(0),
                        usageNode.path("total_tokens").asInt(0));

        return new LlmChatCompletion(
                textOrNull(root.path("id")),
                textOrNull(root.path("model")),
                textOrNull(choice.path("message").path("content")),
                textOrNull(choice.path("finish_reason")),
                usage);
    }

    @Override
    public LlmStreamChunk parseStreamChunk(String line, ObjectMapper objectMapper) {
        return new LlmSseParser(objectMapper).parseLine(line).orElse(null);
    }

    private static JsonNode read(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            throw new LlmInvocationException("Model gateway returned an empty response body");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse the model gateway response", exception);
        }
    }

    static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isEmpty() ? null : value;
    }
}
