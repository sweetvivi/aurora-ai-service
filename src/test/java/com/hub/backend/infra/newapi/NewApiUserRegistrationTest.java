package com.hub.backend.infra.newapi;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract of the public registration endpoint.
 *
 * <p>Registration here is the one New API write that needs no administrator: it posts to the same
 * endpoint the New API sign-up page uses. New API answers a refusal with HTTP 200 and
 * {@code success: false}, so "the name is taken" and "registration is closed upstream" are told
 * apart by the message alone — and getting that wrong either rejects every sign up during an
 * outage or creates accounts New API does not have.</p>
 */
class NewApiUserRegistrationTest {

    private static final String USERNAME = "bob";
    private static final String PASSWORD = "correct-horse-battery";

    private HttpServer server;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private NewApiManagementClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/user/register", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();

        NewApiProperties properties = new NewApiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setLoginTimeout(Duration.ofSeconds(5));
        client = new NewApiManagementClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void postsTheCredentialsWithoutAnyAdministratorCredential() throws IOException {
        responseBody.set("{\"success\":true,\"message\":\"\"}");

        client.registerUser(USERNAME, PASSWORD);

        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertEquals(USERNAME, sent.get("username").asText());
        assertEquals(PASSWORD, sent.get("password").asText());
        assertNull(authorization.get(),
                "registration must not carry an administrator token");
    }

    @Test
    void readsATakenNameAsATakenName() {
        responseBody.set("{\"success\":false,\"message\":"
                + "\"Username already exists or has been deleted\"}");

        NewApiRegistrationException refused = assertThrows(NewApiRegistrationException.class,
                () -> client.registerUser(USERNAME, PASSWORD));

        assertEquals(NewApiRegistrationException.Kind.ALREADY_EXISTS, refused.kind());
    }

    @Test
    void treatsAnEmailVerificationDemandAsItsOwnRefusal() {
        responseBody.set("{\"success\":false,\"message\":"
                + "\"Email verification is enabled, please enter email address and verification code\"}");

        NewApiRegistrationException refused = assertThrows(NewApiRegistrationException.class,
                () -> client.registerUser(USERNAME, PASSWORD));

        assertEquals(NewApiRegistrationException.Kind.EMAIL_VERIFICATION_REQUIRED, refused.kind());
    }

    @Test
    void reportsClosedRegistrationSoTheCallerCanExplainIt() {
        responseBody.set("{\"success\":false,\"message\":"
                + "\"New user registration has been disabled by administrator\"}");

        NewApiRegistrationException refused = assertThrows(NewApiRegistrationException.class,
                () -> client.registerUser(USERNAME, PASSWORD));

        assertEquals(NewApiRegistrationException.Kind.DISABLED, refused.kind());
    }

    @Test
    void neverReadsAGatewayFailureAsATakenName() {
        responseStatus.set(502);
        responseBody.set("<html>bad gateway</html>");

        NewApiRegistrationException refused = assertThrows(NewApiRegistrationException.class,
                () -> client.registerUser(USERNAME, PASSWORD));

        assertEquals(NewApiRegistrationException.Kind.FAILED, refused.kind());
        assertNotNull(refused.getMessage());
        assertFalse(refused.getMessage().toLowerCase().contains("already exists"));
    }

    @Test
    void treatsAUsernameRejectedByValidationAsAFailureRatherThanAClaim() {
        responseStatus.set(400);
        responseBody.set("{\"success\":false,\"message\":\"Invalid input Key: 'Username'\"}");

        NewApiRegistrationException refused = assertThrows(NewApiRegistrationException.class,
                () -> client.registerUser(USERNAME, PASSWORD));

        assertTrue(refused.kind() == NewApiRegistrationException.Kind.FAILED,
                "a validation error is not proof that the name belongs to someone");
    }
}
