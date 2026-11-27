package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioTranscriptionClientTest {

    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private HttpServer server;
    private AudioTranscriptionClient client;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"text\":\"hello there\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setAudioTranscriptionModel("whisper-1");
        properties.setReadTimeout(Duration.ofSeconds(5));
        client = new AudioTranscriptionClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                properties, new ObjectMapper());
    }

    @AfterEach
    void stopStub() { server.stop(0); }

    @Test
    void postsAudioAsMultipartUsingTheUserRelayCredential() {
        String transcript = client.transcribe(new byte[]{1, 2, 3}, "practice.wav", "audio/wav",
                new ProviderCredentials("OPENAI_COMPATIBLE", null, "relay-key", null, null, null));

        String requestBody = new String(body.get(), StandardCharsets.ISO_8859_1);
        assertEquals("hello there", transcript);
        assertEquals("/v1/audio/transcriptions", path.get());
        assertEquals("Bearer relay-key", auth.get());
        assertTrue(requestBody.contains("name=\"model\""));
        assertTrue(requestBody.contains("whisper-1"));
        assertTrue(requestBody.contains("filename=\"practice.wav\""));
        assertTrue(requestBody.contains("audio/wav"));
    }

    @Test
    void refusesAudioWhenNoTranscriptionModelIsConfigured() {
        LlmProperties properties = new LlmProperties();
        properties.setAudioTranscriptionModel("");
        AudioTranscriptionClient disabled = new AudioTranscriptionClient(
                HttpClient.newHttpClient(), properties, new ObjectMapper());

        assertThrows(LlmInvocationException.class, () -> disabled.transcribe(
                new byte[]{1}, "clip.mp3", "audio/mpeg",
                new ProviderCredentials("OPENAI_COMPATIBLE", null, "relay-key", null, null, null)));
    }
}
