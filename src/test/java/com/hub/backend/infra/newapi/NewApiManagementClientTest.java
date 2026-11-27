package com.hub.backend.infra.newapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewApiManagementClientTest {

    private static final String VALID_USAGE = """
            {
              "success": true,
              "data": {
                "name": "hub-default-u1",
                "total_granted": 100,
                "total_used": 20,
                "total_available": 80,
                "unlimited_quota": false,
                "model_limits": {"gpt-image-2": 50},
                "model_limits_enabled": true,
                "expires_at": 1893456000,
                "user_quota": {
                  "total_used": 30,
                  "total_available": 70
                }
              }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> usageAuthorization = new AtomicReference<>();
    private final AtomicReference<String> topUpAuthorization = new AtomicReference<>();
    private final AtomicReference<String> topUpBody = new AtomicReference<>();
    private final AtomicReference<String> relayDeleteAuthorization = new AtomicReference<>();
    private final AtomicReference<String> relayDeleteMethod = new AtomicReference<>();
    private final AtomicReference<String> relayDeletePath = new AtomicReference<>();
    private final AtomicReference<String> consumptionAuthorization = new AtomicReference<>();
    private final AtomicReference<String> consumptionQuery = new AtomicReference<>();
    private final AtomicReference<String> accessTokenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> accessTokenMethod = new AtomicReference<>();
    private final AtomicReference<String> accessTokenPath = new AtomicReference<>();
    private final AtomicReference<String> accessTokenBody = new AtomicReference<>();
    private final AtomicReference<String> accessTokenProof = new AtomicReference<>();
    private final AtomicReference<String> verificationAuthorization = new AtomicReference<>();
    private final AtomicReference<String> verificationBody = new AtomicReference<>();

    private HttpServer server;
    private NewApiManagementClient client;

    private volatile int usageStatus = 200;
    private volatile String usageBody = VALID_USAGE;
    private volatile String encryptionKeyBody = """
            {"success":true,"data":{"enabled":false}}
            """;
    private volatile String loginBody = """
            {
              "success": true,
              "data": {
                "access_token": "login-token",
                "user": {"id": 7, "username": "hub-u1"}
              }
            }
            """;
    private volatile int consumptionStatus = 200;
    private volatile String consumptionBody = """
            {
              "success": true,
              "data": {
                "page": 2,
                "page_size": 20,
                "total": 41,
                "items": [{
                  "id": 901,
                  "created_at": 1893456000,
                  "type": 2,
                  "model_name": "gpt-5",
                  "token_name": "hub-1-u7",
                  "quota": 20548,
                  "prompt_tokens": 120,
                  "completion_tokens": 80,
                  "use_time": 3,
                  "is_stream": true
                }]
              }
            }
            """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/usage/token/", exchange -> {
            usageAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, usageStatus, usageBody);
        });
        server.createContext("/api/user/login/encryption-key",
                exchange -> respond(exchange, 200, encryptionKeyBody));
        server.createContext("/api/user/login",
                exchange -> respond(exchange, 200, loginBody));
        server.createContext("/api/user/topup", exchange -> {
            topUpAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            topUpBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"success":true,"data":2054795}
                    """);
        });
        server.createContext("/api/token/", exchange -> {
            relayDeleteAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            relayDeleteMethod.set(exchange.getRequestMethod());
            relayDeletePath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                    {"success":true}
                    """);
        });
        server.createContext("/api/log/self", exchange -> {
            consumptionAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            consumptionQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, consumptionStatus, consumptionBody);
        });
        server.createContext("/api/verify", exchange -> {
            verificationAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            verificationBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"success":true,"data":{"proof_token":"security-proof"}}
                    """);
        });
        server.createContext("/api/user/access_tokens", exchange -> {
            accessTokenAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            accessTokenMethod.set(exchange.getRequestMethod());
            accessTokenPath.set(exchange.getRequestURI().getPath());
            accessTokenBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            accessTokenProof.set(exchange.getRequestHeaders().getFirst("X-Security-Proof"));
            respond(exchange, 200, """
                    {"success":true}
                    """);
        });
        server.start();

        NewApiProperties properties = new NewApiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setLoginTimeout(Duration.ofSeconds(2));
        properties.setUsageTimeout(Duration.ofSeconds(2));
        properties.setTokenManagementTimeout(Duration.ofSeconds(2));
        client = new NewApiManagementClient(
                properties,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesTheUsersAvailableQuotaAndTokenUsage() {
        NewApiTokenUsage usage = client.fetchUsage("sk-user-relay");

        assertEquals("hub-default-u1", usage.name());
        assertEquals(100, usage.tokenTotalGranted());
        assertEquals(20, usage.tokenTotalUsed());
        assertEquals(80, usage.tokenTotalAvailable());
        assertFalse(usage.unlimitedQuota());
        assertEquals(50, usage.modelLimits().path("gpt-image-2").asInt());
        assertTrue(usage.modelLimitsEnabled());
        assertEquals(1893456000L, usage.expiresAt());
        assertEquals(30, usage.userTotalUsed());
        assertEquals(70, usage.userTotalAvailable());
        assertEquals("Bearer sk-user-relay", usageAuthorization.get());
    }

    @Test
    void acceptsAZeroUsersAvailableQuotaForTheUsageEndpoint() {
        usageBody = VALID_USAGE.replace(
                "\"total_available\": 70", "\"total_available\": 0");

        NewApiTokenUsage usage = client.fetchUsage("sk-user-relay");

        assertEquals(0, usage.userTotalAvailable());
    }

    @Test
    void acceptsNegativeOuterQuotaForAnUnlimitedToken() {
        usageBody = VALID_USAGE
                .replace("\"total_granted\": 100", "\"total_granted\": -1")
                .replace("\"total_available\": 80", "\"total_available\": -1")
                .replace("\"unlimited_quota\": false", "\"unlimited_quota\": true");

        NewApiTokenUsage usage = client.fetchUsage("sk-user-relay");

        assertEquals(0, usage.tokenTotalGranted());
        assertEquals(0, usage.tokenTotalAvailable());
        assertTrue(usage.unlimitedQuota());
        assertEquals(70, usage.userTotalAvailable());
    }

    @Test
    void rejectsNegativeOuterQuotaForALimitedToken() {
        usageBody = VALID_USAGE.replace(
                "\"total_available\": 80", "\"total_available\": -1");

        ServiceUnavailableException exception = assertThrows(
                ServiceUnavailableException.class,
                () -> client.fetchUsage("sk-user-relay"));

        assertTrue(exception.getMessage().contains("total_available"), exception.getMessage());
    }

    @Test
    void rejectsMissingUserQuota() {
        usageBody = """
                {"success":true,"data":{"total_granted":1,"total_used":0,"total_available":1}}
                """;

        ServiceUnavailableException exception = assertThrows(
                ServiceUnavailableException.class,
                () -> client.fetchUsage("sk-user-relay"));

        assertTrue(exception.getMessage().contains("user_quota"), exception.getMessage());
    }

    @Test
    void rejectsStringQuotaValues() {
        usageBody = VALID_USAGE.replace(
                "\"total_available\": 70", "\"total_available\": \"70\"");

        assertInvalidQuota();
    }

    @Test
    void rejectsNegativeQuotaValues() {
        usageBody = VALID_USAGE.replace(
                "\"total_available\": 70", "\"total_available\": -1");

        assertInvalidQuota();
    }

    @Test
    void rejectsQuotaValuesAboveLongRange() {
        usageBody = VALID_USAGE.replace(
                "\"total_available\": 70", "\"total_available\": 9223372036854775808");

        assertInvalidQuota();
    }

    @Test
    void rejectsUsedPlusAvailableOverflow() {
        usageBody = VALID_USAGE
                .replace("\"total_used\": 30", "\"total_used\": 9223372036854775807")
                .replace("\"total_available\": 70", "\"total_available\": 1");

        assertInvalidQuota();
    }

    @Test
    void mapsHttpErrorsToUsageUnavailable() {
        usageStatus = 502;
        usageBody = """
                {"success":false,"message":"upstream down"}
                """;

        ServiceUnavailableException exception = assertThrows(
                ServiceUnavailableException.class,
                () -> client.fetchUsage("sk-user-relay"));

        assertEquals("New API usage service is unavailable", exception.getMessage());
    }

    @Test
    void mapsInvalidJsonToUsageUnavailable() {
        usageBody = "<html>not json</html>";

        ServiceUnavailableException exception = assertThrows(
                ServiceUnavailableException.class,
                () -> client.fetchUsage("sk-user-relay"));

        assertEquals("New API usage service is unavailable", exception.getMessage());
    }

    @Test
    void mapsBusinessFailureToUsageUnavailable() {
        usageBody = """
                {"success":false,"message":"token is disabled"}
                """;

        ServiceUnavailableException exception = assertThrows(
                ServiceUnavailableException.class,
                () -> client.fetchUsage("sk-user-relay"));

        assertEquals("New API usage service is unavailable", exception.getMessage());
    }

    @Test
    void loginRejectsAUserThatDoesNotMatchTheRequestedUsername() {
        loginBody = """
                {
                  "success": true,
                  "data": {
                    "access_token": "login-token",
                    "user": {"id": 8, "username": "hub-u2"}
                  }
                }
                """;

        LlmInvocationException exception = assertThrows(
                LlmInvocationException.class,
                () -> client.login("hub-u1", "password"));

        assertTrue(exception.getMessage().contains("different user"), exception.getMessage());
    }

    @Test
    void redeemsWithTheUsersLoginTokenAndReturnsTheCreditedQuota() throws Exception {
        long quota = client.redeemRedemption("login-token", "ABCD1234EFGH5678");

        assertEquals(2_054_795L, quota);
        assertEquals("Bearer login-token", topUpAuthorization.get());
        var body = objectMapper.readTree(topUpBody.get());
        assertEquals("ABCD1234EFGH5678", body.path("key").asText());
    }

    @Test
    void deletesRelayTokensWithTheUsersPersonalAccessToken() {
        client.deleteRelayToken("sk-user-pat", 99);

        assertEquals("Bearer sk-user-pat", relayDeleteAuthorization.get());
        assertEquals("DELETE", relayDeleteMethod.get());
        assertEquals("/api/token/99", relayDeletePath.get());
    }

    @Test
    void readsConsumptionLogsWithTheUsersPersonalAccessToken() {
        NewApiConsumptionLogPage page =
                client.fetchConsumptionLogs("sk-user-pat", 2, 20);

        assertEquals(2, page.page());
        assertEquals(20, page.pageSize());
        assertEquals(41, page.total());
        NewApiConsumptionLog item = page.items().get(0);
        assertEquals(901, item.id());
        assertEquals(1_893_456_000L, item.createdAt());
        assertEquals("gpt-5", item.modelName());
        assertEquals("hub-1-u7", item.tokenName());
        assertEquals(20_548, item.quota());
        assertEquals(120, item.promptTokens());
        assertEquals(80, item.completionTokens());
        assertEquals(3, item.useTime());
        assertTrue(item.stream());
        assertEquals(2, item.type());
        assertEquals("Bearer sk-user-pat", consumptionAuthorization.get());
        // 不带 type 过滤：失败任务的退款行（type=6）也在同一个列表里
        assertEquals("p=2&page_size=20", consumptionQuery.get());
    }

    @Test
    void acceptsRefundRowsInTheLogPage() {
        consumptionBody = consumptionBody.replace("\"type\": 2", "\"type\": 6");

        NewApiConsumptionLogPage page = client.fetchConsumptionLogs("sk-user-pat", 2, 20);

        assertEquals(6, page.items().get(0).type());
    }

    @Test
    void rejectsConsumptionLogsWithAnUnknownType() {
        consumptionBody = consumptionBody.replace("\"type\": 2", "\"type\": 9");

        assertThrows(ServiceUnavailableException.class,
                () -> client.fetchConsumptionLogs("sk-user-pat", 2, 20));
    }

    @Test
    void rejectsConsumptionLogsWithUnexpectedPagination() {
        consumptionBody = consumptionBody.replace("\"page\": 2", "\"page\": 1");

        assertThrows(ServiceUnavailableException.class,
                () -> client.fetchConsumptionLogs("sk-user-pat", 2, 20));
    }

    @Test
    void rejectsConsumptionLogsWithAnOutOfRangeTimestamp() {
        consumptionBody = consumptionBody.replace(
                "\"created_at\": 1893456000",
                "\"created_at\": 9223372036854775807");

        assertThrows(ServiceUnavailableException.class,
                () -> client.fetchConsumptionLogs("sk-user-pat", 2, 20));
    }

    @Test
    void rejectsConsumptionLogsWithNegativeTokenCounts() {
        consumptionBody = consumptionBody.replace(
                "\"prompt_tokens\": 120",
                "\"prompt_tokens\": -1");

        assertThrows(ServiceUnavailableException.class,
                () -> client.fetchConsumptionLogs("sk-user-pat", 2, 20));
    }

    @Test
    void updatesAccessTokenScopesWithProof() throws Exception {
        client.updateAccessToken(
                "login-token",
                "password",
                88,
                "hub-pat-1-u7",
                List.of(
                        "api_key:read",
                        "api_key:write",
                        "api_key:reveal",
                        "usage:read"));

        assertEquals("Bearer login-token", accessTokenAuthorization.get());
        assertEquals("PATCH", accessTokenMethod.get());
        assertEquals("/api/user/access_tokens/88", accessTokenPath.get());
        assertEquals("security-proof", accessTokenProof.get());
        var body = objectMapper.readTree(accessTokenBody.get());
        assertEquals("hub-pat-1-u7", body.path("name").asText());
        assertEquals(4, body.path("scopes").size());
        assertEquals("usage:read", body.path("scopes").get(3).asText());

        assertEquals("Bearer login-token", verificationAuthorization.get());
        var proofRequest = objectMapper.readTree(verificationBody.get());
        assertEquals("access_token.update", proofRequest.path("scope").asText());
        assertEquals("password", proofRequest.path("password").asText());
        assertEquals(88, proofRequest.path("context").path("token_id").asLong());
        assertEquals(4, proofRequest.path("context").path("scopes").size());
    }

    private void assertInvalidQuota() {
        ServiceUnavailableException exception = assertThrows(
                ServiceUnavailableException.class,
                () -> client.fetchUsage("sk-user-relay"));

        assertTrue(exception.getMessage().contains("quota"), exception.getMessage());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
