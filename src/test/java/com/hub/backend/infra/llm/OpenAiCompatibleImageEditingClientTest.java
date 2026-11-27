package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageEditFile;
import com.hub.backend.infra.llm.dto.ImageEditingCommand;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-contract tests for the multipart {@code /images/edits} adapter.
 *
 * <p>Multipart is fiddly enough that the body itself deserves assertions: a wrong field name or a
 * missing filename is rejected upstream with a message that says nothing about the cause.</p>
 */
class OpenAiCompatibleImageEditingClientTest {

    private static final byte[] SOURCE_BYTES = "SOURCE-IMAGE-BYTES".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MASK_BYTES = "MASK-BYTES".getBytes(StandardCharsets.US_ASCII);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> capturedContentType = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    private HttpServer server;
    private LlmProperties properties;
    private OpenAiCompatibleImageEditingClient client;

    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startStubGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = (com.sun.net.httpserver.HttpHandler) exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        };
        server.createContext("/v1/images/edits", handler);
        // a supplier that declares its own image path, exercised below
        server.createContext("/supplier/v1/images/edits", handler);
        server.start();

        properties = new LlmProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        properties.setApiKey("chat-key-must-not-be-used");
        properties.setImagesPath("/images/generations");
        properties.setImageTimeout(Duration.ofSeconds(10));

        client = new OpenAiCompatibleImageEditingClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                properties,
                objectMapper);
    }

    @AfterEach
    void stopStubGateway() {
        server.stop(0);
    }

    private ImageEditingCommand command() {
        return new ImageEditingCommand(
                "gpt-image-2",
                "replace the background with a studio backdrop",
                new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                null,
                "1024x1024",
                new ProviderCredentials(
                        "OPENAI_COMPATIBLE", null, "sk-user-relay", null, null, null));
    }

    private void stubOneInlineImage() {
        responseBody = """
                {"created":1,"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{9}));
    }

    @Test
    void sendsTheModelPromptImageAndSizeAsMultipart() {
        stubOneInlineImage();

        client.edit(command());

        assertTrue(capturedContentType.get().startsWith("multipart/form-data; boundary="),
                capturedContentType.get());

        String body = capturedBody.get();
        assertTrue(body.contains("name=\"model\"\r\n\r\ngpt-image-2"), body);
        assertTrue(body.contains("name=\"prompt\"\r\n\r\nreplace the background with a studio backdrop"),
                body);
        assertTrue(body.contains("name=\"size\"\r\n\r\n1024x1024"), body);
        assertTrue(body.contains(
                "Content-Disposition: form-data; name=\"image\"; filename=\"photo.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n"), body);
        assertTrue(body.contains("SOURCE-IMAGE-BYTES"), body);
        assertFalse(body.contains("name=\"mask\""), "no mask was requested");
    }

    @Test
    void sendsMultipleReferenceImagesUsingTheRepeatableImageField() {
        stubOneInlineImage();
        ImageEditFile second = new ImageEditFile(
                "SECOND-IMAGE-BYTES".getBytes(StandardCharsets.US_ASCII),
                "second.jpg",
                "image/jpeg");

        client.edit(new ImageEditingCommand(
                "gpt-image-2",
                "combine the references",
                List.of(command().image(), second),
                null,
                "1024x1024",
                relayCredentials()));

        String body = capturedBody.get();
        assertTrue(body.contains(
                "Content-Disposition: form-data; name=\"image[]\"; filename=\"photo.png\""), body);
        assertTrue(body.contains(
                "Content-Disposition: form-data; name=\"image[]\"; filename=\"second.jpg\""), body);
        assertFalse(body.contains("name=\"image\"; filename="), body);
        assertTrue(body.contains("SECOND-IMAGE-BYTES"), body);
    }

    @Test
    void includesTheMaskPartWhenOneIsProvided() {
        stubOneInlineImage();

        client.edit(new ImageEditingCommand(
                "gpt-image-2",
                "remove the object",
                new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                new ImageEditFile(MASK_BYTES, "mask.png", "image/png"),
                "1360x1024",
                relayCredentials()));

        String body = capturedBody.get();
        assertTrue(body.contains("name=\"size\"\r\n\r\n1360x1024"), body);
        assertTrue(body.contains(
                "Content-Disposition: form-data; name=\"mask\"; filename=\"mask.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n"), body);
        assertTrue(body.contains("MASK-BYTES"), body);
        String boundary = capturedContentType.get()
                .substring(capturedContentType.get().indexOf("boundary=") + "boundary=".length());
        assertTrue(body.contains(
                "SOURCE-IMAGE-BYTES\r\n--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"mask\""),
                "the mask must start a new multipart part");
        assertTrue(body.endsWith("--\r\n"), "the multipart body must be closed with the final boundary");
    }

    @Test
    void omitsBlankOptionalsRatherThanSendingEmptyParts() {
        stubOneInlineImage();

        client.edit(new ImageEditingCommand(
                "gpt-image-2",
                "  ",
                new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                null,
                null,
                relayCredentials()));

        String body = capturedBody.get();
        assertFalse(body.contains("name=\"prompt\""), body);
        assertFalse(body.contains("name=\"size\""), body);
    }

    @Test
    void sendsTheSupplierKeyWhenConfigured() {
        stubOneInlineImage();

        client.edit(new ImageEditingCommand(
                "gpt-image-2",
                "cut out the subject",
                new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                null,
                null,
                new ProviderCredentials(null, null, "provider-image-key", null, null, null)));

        assertEquals("Bearer provider-image-key", capturedAuthorization.get());
    }

    @Test
    void sendsTheAccountRelayKeyWhenTheSupplierHasNone() {
        stubOneInlineImage();

        client.edit(command());

        assertEquals("Bearer sk-user-relay", capturedAuthorization.get());
    }

    @Test
    void usesTheSupplierBaseUrlAndDerivesTheEditsPath() {
        stubOneInlineImage();

        client.edit(new ImageEditingCommand(
                "gpt-image-2",
                "upscale this image",
                new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                null,
                null,
                new ProviderCredentials(null,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/supplier/v1",
                        "provider-image-key", null, null, null)));

        // a supplier without an explicit image path is reached on its own host, not on the
        // configured default gateway, because the key belongs to that supplier
        assertEquals("/supplier/v1/images/edits", capturedPath.get());
    }

    @Test
    void derivesTheEditsPathFromASupplierGenerationsPath() {
        stubOneInlineImage();

        client.edit(new ImageEditingCommand(
                "gpt-image-2",
                "upscale this image",
                new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                null,
                null,
                new ProviderCredentials(null,
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "provider-image-key", null, "/images/generations", null)));

        assertEquals("/v1/images/edits", capturedPath.get());
    }

    @Test
    void readsInlineBase64Images() {
        responseBody = """
                {"created":1700000000,"model":"gpt-image-2","data":[{"b64_json":"%s","revised_prompt":"a studio shot"}]}
                """.formatted(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}));

        ImageGenerationResult result = client.edit(command());

        assertEquals(1700000000L, result.createdEpochSecond());
        assertEquals("gpt-image-2", result.model());
        assertEquals(1, result.images().size());

        GeneratedImage image = result.images().get(0);
        assertTrue(image.hasInlineData());
        assertFalse(image.hasUrl());
        assertEquals("a studio shot", image.revisedPrompt());
    }

    @Test
    void acceptsHostedUrlsToo() {
        responseBody = """
                {"created":1700000000,"data":[{"url":"https://cdn.example.com/edited.png"}]}
                """;

        GeneratedImage image = client.edit(command()).images().get(0);

        assertFalse(image.hasInlineData());
        assertTrue(image.hasUrl());
        assertEquals("https://cdn.example.com/edited.png", image.url());
    }

    @Test
    void mapsGatewayErrorsAndNeverEchoesTheKey() {
        responseStatus = 500;
        responseBody = """
                {"error":{"message":"The image editing tool encountered an error.","type":"server_error"}}
                """;

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.edit(command()));

        assertTrue(exception.getMessage().contains("HTTP 500"), exception.getMessage());
        assertFalse(exception.getMessage().contains("sk-user-relay"));
    }

    @Test
    void rejectsAResponseWithoutAnyUsableImage() {
        responseBody = """
                {"created":1,"data":[]}
                """;

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.edit(command()));

        assertTrue(exception.getMessage().contains("no usable image"), exception.getMessage());
    }

    @Test
    void rejectsAnEmptyResponseBody() {
        responseBody = "";

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.edit(command()));

        assertTrue(exception.getMessage().contains("empty response"), exception.getMessage());
    }

    @Test
    void refusesASupplierThatDoesNotSpeakTheOpenAiShape() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.edit(new ImageEditingCommand(
                        "gpt-image-2",
                        "cut out the subject",
                        new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                        null,
                        null,
                        new ProviderCredentials("ANTHROPIC", null, "provider-image-key", null, null, null))));

        assertTrue(exception.getMessage().contains("OpenAI-compatible"), exception.getMessage());
    }

    @Test
    void refusesToCallTheGatewayWithoutASourceImage() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.edit(new ImageEditingCommand(
                        "gpt-image-2", "prompt", new ImageEditFile(new byte[0], "empty.png", "image/png"),
                        null, null, null)));

        assertTrue(exception.getMessage().contains("source image"), exception.getMessage());
    }

    @Test
    void refusesToCallTheGatewayWithoutAnImageKey() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> client.edit(new ImageEditingCommand(
                        "gpt-image-2",
                        "cut out the subject",
                        new ImageEditFile(SOURCE_BYTES, "photo.png", "image/png"),
                        null,
                        null,
                        null)));

        assertTrue(exception.getMessage().contains("relay credential"), exception.getMessage());
    }

    private static ProviderCredentials relayCredentials() {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE", null, "sk-user-relay", null, null, null);
    }
}
