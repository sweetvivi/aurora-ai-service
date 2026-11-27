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
 * Pins the contract of removing a New API user permanently.
 *
 * <p>Self-deletion is not enough to free a username: New API marks the row deleted and then refuses
 * that name forever ("Username already exists or has been deleted"). Only {@code DELETE
 * /api/user/{id}} removes the row, and it needs three things this test locks down — an
 * administrator session, a proof for scope {@code admin.user.delete}, and that proof bound to the
 * target id. Sending it without the context would still pass the proof check for another scope but
 * be refused here, so the binding is asserted explicitly.</p>
 */
class NewApiManagementClientHardDeleteUserTest {

    private static final String PROOF = "admin-proof";
    private static final long TARGET_ID = 7L;

    private HttpServer server;
    private NewApiProperties properties;
    private NewApiManagementClient client;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<JsonNode> verifyBody = new AtomicReference<>();
    private final AtomicReference<String> deleteProof = new AtomicReference<>();
    private final AtomicReference<String> deleteBearer = new AtomicReference<>();
    private final AtomicInteger deleteStatus = new AtomicInteger(200);
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        responses.put("/api/user/login/encryption-key",
                "{\"success\":true,\"data\":{\"enabled\":false}}");
        responses.put("/api/user/login", """
                {"success":true,"data":{"access_token":"admin-token",
                 "user":{"id":1,"username":"root"}}}""");
        responses.put("/api/verify", "{\"success\":true,\"data\":{\"proof_token\":\"" + PROOF + "\"}}");
        responses.put("/api/user/" + TARGET_ID, "{\"success\":true,\"message\":\"\",\"data\":{}}");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        properties = new NewApiProperties();
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
        if (path.startsWith("/api/user/") && method.equals("DELETE")) {
            deleteProof.set(exchange.getRequestHeaders().getFirst("X-Security-Proof"));
            deleteBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
        }

        String payload = responses.getOrDefault(path, "{\"success\":true,\"data\":{}}");
        int status = path.startsWith("/api/user/") && method.equals("DELETE") ? deleteStatus.get() : 200;
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

    private void configureAdministrator() {
        properties.setAdminUsername("root");
        properties.setAdminPassword("admin-secret");
    }

    @Test
    void refusesWithoutAnAdministratorInsteadOfSilentlyLeavingTheNameReserved() {
        // No credential configured: the caller must know the request cannot free anything, rather
        // than getting a quiet no-op that looks like success.
        assertThrows(IllegalStateException.class, () -> client.hardDeleteUser(TARGET_ID));
        assertTrue(calls.isEmpty(), "nothing may reach New API without a credential");
    }

    @Test
    void signsInAsAdministratorThenDeletesWithAProofBoundToTheTarget() {
        configureAdministrator();

        client.hardDeleteUser(TARGET_ID);

        // Encryption-key lookups are cached and repeated per password carrying call, so they are
        // noise here: what matters is that the administrator signs in before the proof is asked
        // for, and that the delete comes last.
        List<String> steps = calls.stream()
                .filter(call -> !call.contains("login/encryption-key"))
                .toList();
        assertEquals(List.of(
                "POST /api/user/login",
                "POST /api/verify",
                "DELETE /api/user/" + TARGET_ID), steps);
        assertEquals("admin.user.delete", verifyBody.get().path("scope").asText());
        assertEquals("password", verifyBody.get().path("method").asText());
        assertEquals("admin-secret", verifyBody.get().path("password").asText());
        assertEquals(TARGET_ID, verifyBody.get().path("context").path("user_id").asLong());
        assertEquals(PROOF, deleteProof.get());
        assertEquals("Bearer admin-token", deleteBearer.get());
    }

    @Test
    void treatsAnAlreadyMissingRowAsSuccess() {
        configureAdministrator();
        deleteStatus.set(404);
        responses.put("/api/user/" + TARGET_ID, "{\"success\":false,\"message\":\"not found\"}");

        // The row being gone is the outcome the caller wants; failing here would turn a retried
        // deletion into an error the operator cannot act on.
        client.hardDeleteUser(TARGET_ID);

        assertTrue(calls.contains("DELETE /api/user/" + TARGET_ID));
    }

    /**
     * New API 的「没这行」是 200 + success:false，不是 404。
     *
     * <p>它出现在自助注销留下的墓碑行上：{@code GetUserById} 默认排除软删行，于是删除接口
     * 先报 record not found。账号已经不可用，没有东西可删，但这行仍然占着用户名 —— 唯一
     * 能做的是不再重试，如实记下来。</p>
     */
    @Test
    void treatsATombstoneReportedInA200BodyAsSuccess() {
        configureAdministrator();
        responses.put("/api/user/" + TARGET_ID,
                "{\"success\":false,\"message\":\"record not found\"}");

        client.hardDeleteUser(TARGET_ID);

        assertTrue(calls.contains("DELETE /api/user/" + TARGET_ID));
    }

    /** 200 里的其它拒绝仍然是拒绝：不能把权限问题当成「已经删了」。 */
    @Test
    void doesNotSwallowOther200Refusals() {
        configureAdministrator();
        responses.put("/api/user/" + TARGET_ID,
                "{\"success\":false,\"message\":\"invalid verification\"}");

        assertThrows(NewApiRejectedException.class, () -> client.hardDeleteUser(TARGET_ID));
    }

    @Test
    void surfacesARealRefusal() {
        configureAdministrator();
        deleteStatus.set(403);
        responses.put("/api/user/" + TARGET_ID,
                "{\"success\":false,\"message\":\"No permission to update users of same or higher permission level\"}");

        NewApiHttpException failure = assertThrows(NewApiHttpException.class,
                () -> client.hardDeleteUser(TARGET_ID));

        assertEquals(403, failure.statusCode());
        assertFalse(failure.getMessage().isBlank());
    }
}
