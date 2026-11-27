package com.hub.backend.infra.newapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the response contract that caused a real bug in password verification.
 *
 * <p>New API answers bad credentials with <b>HTTP 200</b> and {@code {"success": false}}, not with
 * 401. While that body-level refusal was indistinguishable from a gateway failure, "wrong password"
 * and "gateway is down" took the same code path — and the site quietly fell back to its local hash,
 * which is exactly what lets an old password keep working after it was changed on the console.</p>
 */
class NewApiManagementClientLoginTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private NewApiManagementClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/user/login", exchange -> {
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        NewApiProperties properties = new NewApiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setLoginTimeout(Duration.ofSeconds(5));
        client = new NewApiManagementClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void reportsBadCredentialsAsAnAuthenticationFailureDespiteTheOkStatus() {
        body.set("{\"success\":false,\"message\":"
                + "\"Username or password is incorrect, or user has been banned\"}");

        NewApiHttpException failure = assertThrows(NewApiHttpException.class,
                () -> client.login("hub-u1", "wrong"));

        assertEquals(401, failure.statusCode());
        assertTrue(failure.isAuthenticationFailure());
    }

    @Test
    void doesNotReadAnUnparseableBodyAsAWrongPassword() {
        status.set(502);
        body.set("<html>bad gateway</html>");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client.login("hub-u1", "secret"));

        assertFalse(failure instanceof NewApiHttpException && ((NewApiHttpException) failure)
                        .isAuthenticationFailure(),
                "an upstream failure must not be reported as rejected credentials");
    }

    @Test
    void returnsTheSessionForAcceptedCredentials() {
        body.set("{\"success\":true,\"data\":{\"access_token\":\"token-1\","
                + "\"user\":{\"id\":77,\"username\":\"hub-u1\"}}}");

        NewApiLoginSession session = client.login("hub-u1", "secret");

        assertEquals(77, session.userId());
        assertEquals("hub-u1", session.username());
        assertEquals("token-1", session.accessToken());
    }
}
