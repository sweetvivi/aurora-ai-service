package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.protocol.AnthropicChatAdapter;
import com.hub.backend.infra.llm.protocol.ChatProtocol;
import com.hub.backend.infra.llm.protocol.ChatProtocolAdapter;
import com.hub.backend.infra.llm.protocol.GeminiChatAdapter;
import com.hub.backend.infra.llm.protocol.OpenAiChatAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link LlmClient} implementation that speaks whatever protocol the target supplier uses.
 *
 * <p>Dispatches on {@code ProviderCredentials.protocol}; when a model has no supplier, or the
 * supplier does not set a protocol, an OpenAI-compatible gateway is assumed — the behaviour every
 * call had before protocols were honoured.</p>
 *
 * <p>Uses the JDK {@link HttpClient} so no WebFlux stack has to be introduced.</p>
 */
@Component
public class ProtocolAwareLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAwareLlmClient.class);
    private static final int ERROR_BODY_LIMIT = 500;

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<ChatProtocol, ChatProtocolAdapter> adapters;

    public ProtocolAwareLlmClient(HttpClient llmHttpClient, LlmProperties properties, ObjectMapper objectMapper) {
        this.httpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;

        Map<ChatProtocol, ChatProtocolAdapter> registered = new EnumMap<>(ChatProtocol.class);
        for (ChatProtocol protocol : ChatProtocol.values()) {
            registered.put(protocol, switch (protocol) {
                case OPENAI, OPENAI_COMPATIBLE, OLLAMA, AZURE_OPENAI -> new OpenAiChatAdapter(protocol);
                case ANTHROPIC -> new AnthropicChatAdapter();
                case GEMINI -> new GeminiChatAdapter();
            });
        }
        this.adapters = Map.copyOf(registered);
    }

    @Override
    public LlmChatCompletion complete(LlmChatCommand command) {
        ChatProtocolAdapter adapter = adapterFor(command);
        HttpRequest request = adapter.build(command, command.credentials(), properties, objectMapper, false);
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!isSuccessful(response.statusCode())) {
                throw upstreamError(response.statusCode(), response.body());
            }
            return adapter.parseCompletion(response.body(), objectMapper);
        } catch (IOException exception) {
            throw new LlmInvocationException("Model gateway call failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Model gateway call was interrupted", exception);
        }
    }

    @Override
    public void stream(LlmChatCommand command, Consumer<LlmStreamChunk> onChunk) {
        ChatProtocolAdapter adapter = adapterFor(command);
        if (!adapter.supportsStreaming()) {
            // Better than sending a request the other end cannot answer: the caller learns which
            // protocol is at fault and which endpoint to use instead.
            throw new LlmInvocationException(
                    "%s does not support streaming; use POST /api/v1/chat/completions instead"
                            .formatted(adapter.protocol().label()));
        }

        HttpRequest request = adapter.build(command, command.credentials(), properties, objectMapper, true);
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (!isSuccessful(response.statusCode())) {
                String body;
                try (Stream<String> lines = response.body()) {
                    body = lines.collect(Collectors.joining("\n"));
                }
                throw upstreamError(response.statusCode(), body);
            }
            try (Stream<String> lines = response.body()) {
                Iterator<String> iterator = lines.iterator();
                int frames = 0;
                while (iterator.hasNext()) {
                    LlmStreamChunk chunk = adapter.parseStreamChunk(iterator.next(), objectMapper);
                    if (chunk != null) {
                        frames++;
                        onChunk.accept(chunk);
                    }
                }
                log.debug("Streamed {} frames from model {} using {}", frames, command.model(),
                        adapter.protocol().label());
            }
        } catch (IOException exception) {
            throw new LlmInvocationException("Model gateway stream failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Model gateway stream was interrupted", exception);
        }
    }

    private ChatProtocolAdapter adapterFor(LlmChatCommand command) {
        return adapters.get(ChatProtocol.resolve(
                command.credentials() == null ? null : command.credentials().protocol()));
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static LlmInvocationException upstreamError(int statusCode, String body) {
        String snippet = body == null ? "" : body.strip();
        if (snippet.length() > ERROR_BODY_LIMIT) {
            snippet = snippet.substring(0, ERROR_BODY_LIMIT) + "...";
        }
        return new LlmInvocationException(
                "Model gateway returned HTTP %d: %s".formatted(statusCode, snippet),
                statusCode,
                UpstreamFailureText.upstreamCode(body));
    }
}
