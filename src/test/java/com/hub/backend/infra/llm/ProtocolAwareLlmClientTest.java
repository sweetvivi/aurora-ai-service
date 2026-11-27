package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the protocol dispatching client against a local stub gateway, so the wire contract is
 * verified without calling a real provider.
 */
class ProtocolAwareLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<Headers> capturedHeaders = new AtomicReference<>();

    private HttpServer server;
    private LlmProperties properties;
    private ProtocolAwareLlmClient client;

    private volatile int responseStatus = 200;
    private volatile String responseContentType = "application/json";
    private volatile String responseBody = "{}";

    @BeforeEach
    void startStubGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedHeaders.set(exchange.getRequestHeaders());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", responseContentType);
            exchange.sendResponseHeaders(responseStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        };
        server.createContext("/v1/chat/completions", handler);
        // a supplier that declares its own path, exercised by the provider-credentials tests
        server.createContext("/supplier/v1/chat/completions", handler);
        server.start();

        properties = new LlmProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        properties.setApiKey("test-key");
        properties.setDefaultModel("deepseek-v4.1-flash");
        properties.setReadTimeout(Duration.ofSeconds(10));

        client = new ProtocolAwareLlmClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                properties,
                objectMapper);
    }

    @AfterEach
    void stopStubGateway() {
        server.stop(0);
    }

    @Test
    void sendsBearerTokenAndParsesBlockingCompletion() throws Exception {
        responseBody = """
                {"choices":[{"finish_reason":"stop","index":0,"message":{"content":"pong","role":"assistant"}}],\
                "created":1791967720,"id":"230820bf","model":"deepseek-v4.1-flash",\
                "object":"chat.completion","usage":{"prompt_tokens":4455,"completion_tokens":3,"total_tokens":4458}}""";

        LlmChatCompletion completion = client.complete(command(null, null));

        assertEquals("pong", completion.content());
        assertEquals("deepseek-v4.1-flash", completion.model());
        assertEquals("stop", completion.finishReason());
        assertEquals(4455, completion.usage().promptTokensOrZero());
        assertEquals(4458, completion.usage().totalTokensOrZero());

        assertEquals("/v1/chat/completions", capturedPath.get());
        assertEquals("Bearer test-key", capturedHeaders.get().getFirst("Authorization"));

        JsonNode sent = objectMapper.readTree(capturedBody.get());
        assertEquals("deepseek-v4.1-flash", sent.get("model").asText());
        assertFalse(sent.get("stream").asBoolean());
        assertFalse(sent.has("temperature"));
        assertFalse(sent.has("max_tokens"));
        assertEquals(2, sent.get("messages").size());
    }

    @Test
    void forwardsOptionalSamplingParameters() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

        client.complete(command(0.3, 512));

        JsonNode sent = objectMapper.readTree(capturedBody.get());
        assertEquals(0.3, sent.get("temperature").asDouble());
        assertEquals(512, sent.get("max_tokens").asInt());
    }

    @Test
    void mapsUpstreamErrorWithoutLeakingTheApiKey() {
        responseStatus = 401;
        responseBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.complete(command(null, null)));

        assertTrue(exception.getMessage().contains("401"));
        assertTrue(exception.getMessage().contains("invalid api key"));
        assertFalse(exception.getMessage().contains("test-key"));
    }

    @Test
    void failsFastWhenTheApiKeyIsMissing() {
        properties.setApiKey("  ");

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.complete(command(null, null)));

        assertTrue(exception.getMessage().contains("api key"));
    }

    @Test
    void rejectsUnusableSuccessBody() {
        responseStatus = 200;
        responseBody = "not-json";

        assertThrows(LlmInvocationException.class, () -> client.complete(command(null, null)));
    }

    @Test
    void streamsContentAndReasoningIncrements() throws Exception {
        responseContentType = "text/event-stream";
        responseBody = """
                data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null,"index":0}],"id":"s1"}

                data: {"choices":[{"delta":{"reasoning_content":"think"},"finish_reason":null,"index":0}],"id":"s1"}

                data: {"choices":[{"delta":{"content":"Hel"},"finish_reason":null,"index":0}],"id":"s1"}

                data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop","index":0}],"id":"s1"}

                data: [DONE]

                """;

        List<LlmStreamChunk> chunks = new ArrayList<>();
        client.stream(command(null, null), chunks::add);

        assertEquals(5, chunks.size());
        assertEquals("think", chunks.get(1).reasoningDelta());
        assertEquals("Hel", chunks.get(2).contentDelta());
        assertEquals("lo", chunks.get(3).contentDelta());
        assertEquals("stop", chunks.get(3).finishReason());
        assertTrue(chunks.get(4).done());

        JsonNode sent = objectMapper.readTree(capturedBody.get());
        assertTrue(sent.get("stream").asBoolean());
        assertEquals("text/event-stream", capturedHeaders.get().getFirst("Accept"));
    }

    @Test
    void reportsStreamingUpstreamError() {
        responseStatus = 503;
        responseContentType = "text/event-stream";
        responseBody = "{\"error\":\"upstream unavailable\"}";

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.stream(command(null, null), chunk -> {
                }));

        assertTrue(exception.getMessage().contains("503"));
    }

    @Test
    void usesTheSupplierCredentialsWhenTheModelHasAProvider() {
        responseBody = """
                {"id":"cmpl-1","model":"m","choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}
                """;
        client.complete(new LlmChatCommand(
                "deepseek-v4.1-flash",
                List.of(LlmChatMessage.user("hi")),
                null,
                null,
                new ProviderCredentials(null,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/supplier/v1",
                        "provider-store-key", "/chat/completions", null, null)));

        // the key stored on the supplier wins over the configured one, and the supplier's own path
        // is used instead of the service default
        assertEquals("Bearer provider-store-key",
                capturedHeaders.get().getFirst("Authorization"));
        assertEquals("/supplier/v1/chat/completions", capturedPath.get());
    }

    @Test
    void fallsBackToTheConfiguredKeyWhenTheSupplierHasNone() {
        responseBody = """
                {"id":"cmpl-1","model":"m","choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}
                """;
        client.complete(new LlmChatCommand(
                "deepseek-v4.1-flash",
                List.of(LlmChatMessage.user("hi")),
                null,
                null,
                new ProviderCredentials(null, null, null, null, null, null)));

        assertEquals("Bearer test-key", capturedHeaders.get().getFirst("Authorization"));
    }

    private static LlmChatCommand command(Double temperature, Integer maxTokens) {
        return new LlmChatCommand(
                "deepseek-v4.1-flash",
                List.of(LlmChatMessage.system("be brief"), LlmChatMessage.user("reply pong")),
                temperature,
                maxTokens,
                null);
    }
}
