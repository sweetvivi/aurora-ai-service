package com.hub.backend.infra.newapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two-step contract of deleting a New API account.
 *
 * <p>{@code DELETE /api/user/self} is not a plain delete: New API answers
 * {@code SECURITY_PROOF_REQUIRED} unless the request carries an {@code X-Security-Proof} header,
 * and that proof can only be obtained by first posting the password to {@code /api/verify} with
 * scope {@code account.delete}. Sending the delete without the proof is the mistake this test
 * guards against — it fails closed, but with a confusing 403 that looks like a permission problem
 * rather than a missing step.</p>
 */
class NewApiManagementClientDeleteUserTest {

    private static final String PROOF = "proof-token";

    private HttpServer server;
    private NewApiManagementClient client;
    /** Paths in the order the client called them. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** Body of POST /api/verify, for asserting the scope and password. */
    private final AtomicReference<JsonNode> verifyBody = new AtomicReference<>();
    /** Value of X-Security-Proof on the delete call, null when the header was absent. */
    private final AtomicReference<String> deleteProof = new AtomicReference<>();
    /** Status the delete endpoint answers with. */
    private final AtomicInteger deleteStatus = new AtomicInteger(200);
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        responses.put("/api/user/login/encryption-key", "{\"success\":true,\"data\":{\"enabled\":false}}");
        responses.put("/api/verify", "{\"success\":true,\"data\":{\"proof_token\":\"" + PROOF + "\"}}");
        responses.put("/api/user/self", "{\"success\":true,\"message\":\"\",\"data\":{}}");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        NewApiProperties properties = new NewApiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setLoginTimeout(Duration.ofSeconds(5));
        properties.setUserAccessTokenTimeout(Duration.ofSeconds(5));
        client = new NewApiManagementClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        calls.add(method + " " + path);

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/api/verify")) {
            verifyBody.set(new ObjectMapper().readTree(requestBody));
        }
        if (path.equals("/api/user/self") && method.equals("DELETE")) {
            String proof = exchange.getRequestHeaders().getFirst("X-Security-Proof");
            deleteProof.set(proof);
            // Mirror New API: without the proof header the request is refused.
            if (proof == null || proof.isBlank()) {
                write(exchange, 403, """
                        {"success":false,"code":"SECURITY_PROOF_REQUIRED",
                         "message":"Additional verification required"}""");
                return;
            }
        }

        String payload = responses.getOrDefault(path, "{\"success\":true,\"data\":{}}");
        int status = path.equals("/api/user/self") ? deleteStatus.get() : 200;
        write(exchange, status, payload);
    }

    private static void write(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void verifiesWithTheDeleteScopeBeforeDeleting() {
        client.deleteUser("dashboard-token", "secret");

        assertEquals(List.of(
                "GET /api/user/login/encryption-key",
                "POST /api/verify",
                "DELETE /api/user/self"), calls);
        assertEquals("account.delete", verifyBody.get().path("scope").asText());
        assertEquals("password", verifyBody.get().path("method").asText());
        assertEquals("secret", verifyBody.get().path("password").asText());
        assertEquals(PROOF, deleteProof.get());
    }

    @Test
    void omitsTheContextFieldEntirelyForScopesWithoutParameters() {
        client.deleteUser("dashboard-token", "secret");

        // New API decodes context into json.RawMessage; the literal null arrives as a non-empty
        // raw value and is rejected as "not an object" (SECURITY_CONTEXT_INVALID). Account
        // deletion has no context, so the field must be absent rather than null.
        assertFalse(verifyBody.get().has("context"),
                "account.delete must not send a context field at all");
    }

    @Test
    void doesNotReachTheDeleteEndpointWhenVerificationIsRefused() {
        responses.put("/api/verify", """
                {"success":false,"code":"SECURITY_ACTION_FORBIDDEN",
                 "message":"You do not have permission to perform this action."}""");

        // A body-level refusal surfaces as NewApiRejectedException, not an HTTP failure.
        assertThrows(RuntimeException.class, () -> client.deleteUser("dashboard-token", "secret"));

        assertFalse(calls.contains("DELETE /api/user/self"),
                "the delete must not fire without a proof");
    }

    @Test
    void surfacesTheRootAccountRefusal() {
        deleteStatus.set(403);
        responses.put("/api/user/self",
                "{\"success\":false,\"message\":\"Cannot delete super administrator account\"}");

        NewApiHttpException failure = assertThrows(NewApiHttpException.class,
                () -> client.deleteUser("dashboard-token", "secret"));

        assertEquals(403, failure.statusCode());
        assertTrue(failure.getMessage().contains("super administrator"));
    }
}
