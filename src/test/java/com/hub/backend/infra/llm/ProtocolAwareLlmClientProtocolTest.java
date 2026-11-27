package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
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
 * Verifies that a supplier's declared protocol actually changes the request that leaves the process.
 *
 * <p>Before protocols were honoured every supplier was called as an OpenAI gateway, so an
 * Anthropic or Gemini provider received a body it could not parse. These tests pin the wire format
 * of each protocol against a local stub.</p>
 */
class ProtocolAwareLlmClientProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> capturedHeaders = new AtomicReference<>();

    private HttpServer server;
    private LlmProperties properties;
    private ProtocolAwareLlmClient client;

    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedQuery.set(exchange.getRequestURI().getQuery());
            capturedHeaders.set(exchange.getRequestHeaders());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        };
        // one context per protocol prefix, plus the bare OpenAI-compatible chat path
        for (String prefix : List.of("/chat/completions", "/openai/", "/v1beta/", "/v1/")) {
            server.createContext(prefix, handler);
        }
        server.start();

        properties = new LlmProperties();
        properties.setBaseUrl(baseUrl());
        properties.setApiKey("configured-key");
        properties.setReadTimeout(Duration.ofSeconds(10));

        client = new ProtocolAwareLlmClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), properties, MAPPER);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LlmChatCommand command(ProviderCredentials credentials) {
        return new LlmChatCommand("some-model",
                List.of(LlmChatMessage.system("be brief"), LlmChatMessage.user("say pong")),
                0.2, null, credentials);
    }

    private ProviderCredentials credentials(String protocol, String apiVersion) {
        return new ProviderCredentials(protocol, baseUrl(), "supplier-key", null, null, apiVersion);
    }

    // ---------------------------------------------------------------- OpenAI family

    @Test
    void sendsConfiguredThinkingParameterWhenRequested() throws Exception {
        var command = new LlmChatCommand("some-model", List.of(LlmChatMessage.user("say pong")),
                null, null, credentials("OPENAI_COMPATIBLE", null), List.of(), List.of(),
                true, "enable_thinking", null, null);
        client.complete(command);

        JsonNode body = MAPPER.readTree(capturedBody.get());
        assertTrue(body.path("enable_thinking").asBoolean());
    }

    @Test
    void sendsFalseWhenThinkingIsTurnedOffButConfigured() throws Exception {
        var command = new LlmChatCommand("some-model", List.of(LlmChatMessage.user("say pong")),
                null, null, credentials("OPENAI_COMPATIBLE", null), List.of(), List.of(),
                false, "enable_thinking", null, null);
        client.complete(command);

        JsonNode body = MAPPER.readTree(capturedBody.get());
        assertFalse(body.path("enable_thinking").asBoolean());
    }

    @Test
    void sendsConfiguredStringThinkingValues() throws Exception {
        var command = new LlmChatCommand("some-model", List.of(LlmChatMessage.user("say pong")),
                null, null, credentials("OPENAI_COMPATIBLE", null), List.of(), List.of(),
                true, "reasoning_effort", "medium", "none");
        client.complete(command);

        JsonNode body = MAPPER.readTree(capturedBody.get());
        assertEquals("medium", body.path("reasoning_effort").asText());
    }

    @Test
    void sendsDeepSeekThinkingObjectWhenConfigured() throws Exception {
        var command = new LlmChatCommand("some-model", List.of(LlmChatMessage.user("say pong")),
                null, null, credentials("OPENAI_COMPATIBLE", null), List.of(), List.of(),
                false, "thinking", "{\"type\":\"enabled\"}", "{\"type\":\"disabled\"}");
        client.complete(command);

        JsonNode body = MAPPER.readTree(capturedBody.get());
        assertEquals("disabled", body.path("thinking").path("type").asText());
    }

    @Test
    void omitsThinkingFlagWhenNotConfigured() throws Exception {
        var command = new LlmChatCommand("some-model", List.of(LlmChatMessage.user("say pong")),
                null, null, credentials("OPENAI_COMPATIBLE", null), List.of(), List.of(),
                true, null, null, null);
        client.complete(command);

        JsonNode body = MAPPER.readTree(capturedBody.get());
        assertTrue(body.path("enable_thinking").isMissingNode());
    }

    @Test
    void callsAnOpenAiCompatibleSupplierAtItsChatPath() throws Exception {
        responseBody = """
                {"id":"c1","model":"some-model","choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}
                """;

        LlmChatCompletion completion = client.complete(command(credentials("OPENAI_COMPATIBLE", null)));

        assertEquals("pong", completion.content());
        assertEquals("/chat/completions", capturedPath.get());
        assertEquals("Bearer supplier-key", capturedHeaders.get().getFirst("Authorization"));
    }

    @Test
    void routesAzureThroughItsDeploymentPathAndHeader() throws Exception {
        responseBody = """
                {"id":"c1","choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}
                """;

        client.complete(command(credentials("AZURE_OPENAI", "2026-10-01")));

        assertEquals("/openai/deployments/some-model/chat/completions", capturedPath.get());
        assertEquals("api-version=2026-10-01", capturedQuery.get());
        assertEquals("supplier-key", capturedHeaders.get().getFirst("api-key"));
        // Azure uses api-key, not a bearer token
        assertEquals(null, capturedHeaders.get().getFirst("Authorization"));
    }

    @Test
    void refusesAzureWithoutAnApiVersion() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.complete(command(credentials("AZURE_OPENAI", null))));

        assertTrue(exception.getMessage().contains("apiVersion"), exception.getMessage());
    }

    // ---------------------------------------------------------------- Anthropic

    @Test
    void speaksTheAnthropicMessagesApi() throws Exception {
        responseBody = """
                {"id":"msg_1","model":"some-model","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"po"},{"type":"text","text":"ng"}],
                 "usage":{"input_tokens":11,"output_tokens":2}}
                """;

        LlmChatCompletion completion = client.complete(command(credentials("ANTHROPIC", null)));

        assertEquals("/v1/messages", capturedPath.get());
        assertEquals("supplier-key", capturedHeaders.get().getFirst("x-api-key"));
        assertEquals("2023-06-01", capturedHeaders.get().getFirst("anthropic-version"));
        assertEquals(null, capturedHeaders.get().getFirst("Authorization"));

        JsonNode sent = MAPPER.readTree(capturedBody.get());
        // Anthropic requires max_tokens and rejects a system entry inside messages
        assertTrue(sent.get("max_tokens").asInt() > 0, "max_tokens must always be sent");
        assertFalse(sent.has("messages") && sent.get("messages").findValues("system").size() > 0);
        assertEquals("be brief", sent.get("system").asText());
        assertEquals(1, sent.get("messages").size());
        assertEquals("end_turn", completion.finishReason());
        assertEquals(13, completion.usage().totalTokens());
        // text blocks are concatenated
        assertEquals("pong", completion.content());
    }

    @Test
    void honoursAProviderSpecificAnthropicVersion() throws Exception {
        responseBody = """
                {"id":"m","content":[{"type":"text","text":"ok"}]}
                """;

        client.complete(command(credentials("ANTHROPIC", "2025-01-01")));

        assertEquals("2025-01-01", capturedHeaders.get().getFirst("anthropic-version"));
    }

    @Test
    void readsAnthropicStreamFrames() {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        responseBody = "";

        // drive the parser directly: the stream loop needs a line body, and the frame shapes are the
        // thing under test
        var adapter = new com.hub.backend.infra.llm.protocol.AnthropicChatAdapter();
        chunks.add(adapter.parseStreamChunk("event: content_block_delta", MAPPER));
        chunks.add(adapter.parseStreamChunk(
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"po\"}}",
                MAPPER));
        chunks.add(adapter.parseStreamChunk(
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ng\"}}",
                MAPPER));
        chunks.add(adapter.parseStreamChunk("data: {\"type\":\"ping\"}", MAPPER));
        chunks.add(adapter.parseStreamChunk(
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}", MAPPER));
        chunks.add(adapter.parseStreamChunk("data: {\"type\":\"message_stop\"}", MAPPER));

        assertTrue(chunks.get(0) == null, "an event: line carries no payload");
        assertEquals("po", chunks.get(1).contentDelta());
        assertEquals("ng", chunks.get(2).contentDelta());
        assertTrue(chunks.get(3) == null, "ping carries no visible text");
        assertEquals("end_turn", chunks.get(4).finishReason());
        assertTrue(chunks.get(5).done());
    }

    // ---------------------------------------------------------------- Gemini

    @Test
    void speaksTheGeminiGenerateContentApi() throws Exception {
        responseBody = """
                {"responseId":"r1","modelVersion":"gemini-3",
                 "candidates":[{"content":{"parts":[{"text":"po"},{"text":"ng"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2,"totalTokenCount":9}}
                """;

        LlmChatCompletion completion = client.complete(command(credentials("GEMINI", null)));

        assertEquals("/v1beta/models/some-model:generateContent", capturedPath.get());
        assertEquals("supplier-key", capturedHeaders.get().getFirst("x-goog-api-key"));

        JsonNode sent = MAPPER.readTree(capturedBody.get());
        // system goes out of band, and assistant turns are called "model" in this vocabulary
        assertEquals("be brief", sent.get("systemInstruction").get("parts").get(0).get("text").asText());
        assertEquals("user", sent.get("contents").get(0).get("role").asText());
        assertEquals("say pong", sent.get("contents").get(0).get("parts").get(0).get("text").asText());
        assertEquals(0.2, sent.get("generationConfig").get("temperature").asDouble());

        assertEquals("pong", completion.content());
        assertEquals(9, completion.usage().totalTokens());
        assertEquals("STOP", completion.finishReason());
    }

    @Test
    void mapsAssistantTurnsToGeminiModelRole() throws Exception {
        responseBody = """
                {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}
                """;

        client.complete(new LlmChatCommand("some-model",
                List.of(LlmChatMessage.user("hi"), LlmChatMessage.assistant("hello"),
                        LlmChatMessage.user("again")),
                null, 64, credentials("GEMINI", null)));

        JsonNode contents = MAPPER.readTree(capturedBody.get()).get("contents");
        assertEquals("user", contents.get(0).get("role").asText());
        assertEquals("model", contents.get(1).get("role").asText());
        assertEquals(64, MAPPER.readTree(capturedBody.get())
                .get("generationConfig").get("maxOutputTokens").asInt());
    }

    @Test
    void readsGeminiStreamFrames() {
        var adapter = new com.hub.backend.infra.llm.protocol.GeminiChatAdapter();

        LlmStreamChunk first = adapter.parseStreamChunk(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"po\"}]}}]}", MAPPER);
        LlmStreamChunk last = adapter.parseStreamChunk(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ng\"}]},\"finishReason\":\"STOP\"}]}",
                MAPPER);

        assertEquals("po", first.contentDelta());
        assertEquals("ng", last.contentDelta());
        assertEquals("STOP", last.finishReason());
    }

    // ---------------------------------------------------------------- fallbacks

    @Test
    void treatsAnUnknownProtocolAsOpenAiCompatible() throws Exception {
        responseBody = """
                {"choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}
                """;

        client.complete(command(new ProviderCredentials("SOME_NEW_THING", baseUrl(), "k", null, null, null)));

        assertEquals("/chat/completions", capturedPath.get());
    }

    @Test
    void fallsBackToTheConfiguredGatewayWithoutASupplier() throws Exception {
        responseBody = """
                {"choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}
                """;

        client.complete(new LlmChatCommand("some-model", List.of(LlmChatMessage.user("hi")),
                null, null, null));

        // no supplier means the configured defaults, which is the pre-existing behaviour
        assertEquals("Bearer configured-key", capturedHeaders.get().getFirst("Authorization"));
    }

    @Test
    void mapsProtocolErrorsWithoutEchoingTheKey() {
        responseStatus = 401;
        responseBody = """
                {"error":{"message":"invalid api key"}}
                """;

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.complete(command(credentials("ANTHROPIC", null))));

        assertTrue(exception.getMessage().contains("HTTP 401"), exception.getMessage());
        assertFalse(exception.getMessage().contains("supplier-key"));
    }
}
