package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageGenerationCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the image adapter against a local stub gateway.
 *
 * <p>The point is the wire contract: the account relay key is used, supplier routing is respected,
 * and both response shapes are accepted. The real gateway returns inline base64, but the contract
 * also allows a hosted URL.</p>
 */
class OpenAiCompatibleImageGenerationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    private HttpServer server;
    private LlmProperties properties;
    private OpenAiCompatibleImageGenerationClient client;

    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startStubGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        };
        server.createContext("/v1/images/generations", handler);
        // a supplier that declares its own image path, exercised below
        server.createContext("/supplier/v1/images/generations", handler);
        server.start();

        properties = new LlmProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        properties.setApiKey("chat-key-must-not-be-used");
        properties.setImageModel("gpt-image-2");
        properties.setImageTimeout(Duration.ofSeconds(10));

        client = new OpenAiCompatibleImageGenerationClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                properties,
                objectMapper);
    }

    @AfterEach
    void stopStubGateway() {
        server.stop(0);
    }

    private ImageGenerationCommand command() {
        return new ImageGenerationCommand(
                "gpt-image-2",
                "a blue circle",
                "1024x1024",
                "medium",
                1,
                new ProviderCredentials(
                        "OPENAI_COMPATIBLE", null, "sk-user-relay", null, null, null));
    }

    @Test
    void readsInlineBase64Images() {
        String png = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        responseBody = """
                {"created":1700000000,"model":"gpt-image-2","data":[{"b64_json":"%s","revised_prompt":"a blue circle, centred"}]}
                """.formatted(png);

        ImageGenerationResult result = client.generate(command());

        assertEquals(1700000000L, result.createdEpochSecond());
        assertEquals("gpt-image-2", result.model());
        assertEquals(1, result.images().size());

        GeneratedImage image = result.images().get(0);
        assertTrue(image.hasInlineData());
        assertFalse(image.hasUrl());
        assertEquals("a blue circle, centred", image.revisedPrompt());
    }

    @Test
    void acceptsHostedUrlsToo() {
        responseBody = """
                {"created":1700000000,"data":[{"url":"https://cdn.example.com/a.png"}]}
                """;

        GeneratedImage image = client.generate(command()).images().get(0);

        assertFalse(image.hasInlineData());
        assertTrue(image.hasUrl());
        assertEquals("https://cdn.example.com/a.png", image.url());
    }

    @Test
    void sendsTheAccountRelayKey() {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(command());

        assertEquals("Bearer sk-user-relay", capturedAuthorization.get());
    }

    @Test
    void sendsThePromptAndTheOptions() throws Exception {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(command());

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertEquals("gpt-image-2", body.get("model").asText());
        assertEquals("a blue circle", body.get("prompt").asText());
        assertEquals("1024x1024", body.get("size").asText());
        assertEquals("medium", body.get("quality").asText());
        assertEquals(1, body.get("n").asInt());
    }

    @Test
    void omitsOptionalFieldsWhenNotRequested() throws Exception {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(new ImageGenerationCommand(
                "gpt-image-2", "a blue circle", null, null, 2, relayCredentials()));

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertFalse(body.has("size"), "size must be omitted rather than sent as null");
        assertFalse(body.has("quality"), "quality must be omitted rather than sent as null");
        assertEquals(2, body.get("n").asInt());
    }

    @Test
    void omitsTheAutoLiteralBecauseTheGatewayRejectsIt() throws Exception {
        // 网关原文：400 auto aspect requires a Gemini image model and reference images
        //           400 unsupported quality; use an explicit resolution
        // auto 只表示「交给上游决定」，不能原样发出去；真实画质照常透传。
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(new ImageGenerationCommand(
                "gpt-image-2", "a blue circle", "auto", "high", 1, relayCredentials()));

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertFalse(body.has("size"), "auto 表示交给上游决定，不是一个可以发出去的字面值");
        assertEquals("high", body.get("quality").asText());
    }

    @Test
    void mapsGatewayErrorsAndNeverEchoesTheKey() {
        responseStatus = 500;
        responseBody = """
                {"error":{"message":"The image generation tool encountered an error.","type":"server_error"}}
                """;

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.generate(command()));

        assertTrue(exception.getMessage().contains("HTTP 500"), exception.getMessage());
        assertFalse(exception.getMessage().contains("sk-user-relay"));
    }

    @Test
    void rejectsAResponseWithoutAnyUsableImage() {
        responseBody = """
                {"created":1,"data":[]}
                """;

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.generate(command()));

        assertTrue(exception.getMessage().contains("no usable image"), exception.getMessage());
    }

    @Test
    void usesTheSupplierCredentialsWhenTheImageModelHasAProvider() {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));
        capturedAuthorization.set(null);

        client.generate(new ImageGenerationCommand("gpt-image-2", "a blue circle", null, null, 1,
                new ProviderCredentials(null, null, "provider-image-key", null, null, null)));

        assertEquals("Bearer provider-image-key", capturedAuthorization.get());
    }

    @Test
    void usesTheSupplierOwnImagePathWhenItDeclaresOne() {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(new ImageGenerationCommand("gpt-image-2", "a blue circle", null, null, 1,
                new ProviderCredentials(null,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/supplier/v1",
                        "provider-image-key", null, "/images/generations", null)));

        // a supplier that lives at another path is reached at that path, not at app.llm.images-path
        assertEquals("/supplier/v1/images/generations", capturedPath.get());
    }

    @Test
    void fallsBackToTheConfiguredImagePathWhenTheSupplierHasNone() {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(new ImageGenerationCommand("gpt-image-2", "a blue circle", null, null, 1,
                new ProviderCredentials(null, null, "provider-image-key", null, null, null)));

        assertEquals("/v1/images/generations", capturedPath.get());
    }

    @Test
    void keepsTheSupplierHostWhenItDeclaresABaseUrlButNoImagePath() {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));

        client.generate(new ImageGenerationCommand("gpt-image-2", "a blue circle", null, null, 1,
                new ProviderCredentials(null,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/supplier/v1",
                        "provider-image-key", null, null, null)));

        // the default path is appended to this supplier's host; the configured default gateway must
        // never receive another supplier's key
        assertEquals("/supplier/v1/images/generations", capturedPath.get());
    }

    @Test
    void refusesToCallTheGatewayWithoutAnImageKey() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.generate(new ImageGenerationCommand(
                        "gpt-image-2", "a blue circle", null, null, 1, null)));

        assertTrue(exception.getMessage().contains("relay credential"), exception.getMessage());
    }

    private static ProviderCredentials relayCredentials() {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE", null, "sk-user-relay", null, null, null);
    }
}
