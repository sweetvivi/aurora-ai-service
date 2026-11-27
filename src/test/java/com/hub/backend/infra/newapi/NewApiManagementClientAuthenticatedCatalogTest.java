package com.hub.backend.infra.newapi;

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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalogue sync against a plaza that requires a session.
 *
 * <p>New API's {@code pricing} header-nav module can be switched to {@code requireAuth}; from then
 * on {@code GET /api/pricing} answers 401 to anonymous callers and the sync has to sign in as its
 * own account first.</p>
 */
class NewApiManagementClientAuthenticatedCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger logouts = new AtomicInteger();
    /** 登录响应里 access_expires_at 相对现在多少秒；负数用来模拟「已过期」。 */
    private final AtomicInteger sessionLifetimeSeconds = new AtomicInteger(3600);
    private final AtomicInteger pricingCalls = new AtomicInteger();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPassword = new AtomicReference<>();
    private final AtomicInteger pricingRejectionsLeft = new AtomicInteger();

    private HttpServer server;
    private NewApiManagementClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/user/login/encryption-key", this::encryptionKey);
        server.createContext("/api/user/login", this::login);
        server.createContext("/api/user/logout", this::logout);
        server.createContext("/api/pricing", this::pricing);
        server.start();

        NewApiProperties properties = new NewApiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCatalogTimeout(Duration.ofSeconds(2));
        properties.setCatalogUsername("catalog-bot");
        properties.setCatalogPassword("secret");
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
    void signsInBeforeReadingAPlazaThatRequiresALogin() {
        NewApiModelPlaza plaza = client.fetchModelPlaza();

        assertEquals(1, logins.get());
        assertEquals("secret", lastPassword.get());
        assertEquals("Bearer session-1", lastAuthorization.get());
        assertEquals(1, plaza.groups().size());
        NewApiModelPlaza.Model model = plaza.groups().get(0).models().get(0);
        assertEquals("deepseek-chat", model.name());
        assertNotNull(model.pricing());
    }

    @Test
    void signsInAgainWhenTheCachedSessionIsRejected() {
        client.fetchModelPlaza();
        assertEquals(1, logins.get());
        assertEquals("Bearer session-1", lastAuthorization.get());

        // New API revoked the session: the next read is answered 401, which must trigger exactly
        // one fresh sign-in rather than failing the whole sync.
        pricingRejectionsLeft.set(1);
        client.fetchModelPlaza();

        assertEquals(2, logins.get());
        assertEquals("Bearer session-2", lastAuthorization.get());
        assertEquals(3, pricingCalls.get());
        // 换新会话前先把旧的登出，否则站点的登录会话数（AUTH_SESSION_LIMIT）越攒越满。
        assertEquals(1, logouts.get());
    }

    @Test
    void renewsTheSessionBeforeItExpiresInsteadOfWaitingForA401() {
        // 站点给的令牌只有 1 小时；这里让它一拿到就过期，模拟「缓存里的会话已经过期」。
        sessionLifetimeSeconds.set(-120);
        client.fetchModelPlaza();
        assertEquals(1, logins.get());
        assertEquals(0, logouts.get());

        // 第二次读取应该主动续期（先登出再登录），而不是发一个注定 401 的请求、
        // 再靠 401 触发重登——那会多烧一个会话槽位。
        client.fetchModelPlaza();

        assertEquals(2, logins.get());
        assertEquals(1, logouts.get());
    }

    @Test
    void signsInOnlyOnceAcrossRepeatedReads() {
        client.fetchModelPlaza();
        client.fetchModelPlaza();
        client.fetchModelPlaza();

        assertEquals(1, logins.get());
        assertEquals(3, pricingCalls.get());
        assertEquals(0, logouts.get());
    }

    @Test
    void keepsAnonymousReadsWhenNoCatalogAccountIsConfigured() {
        NewApiProperties anonymous = new NewApiProperties();
        anonymous.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        anonymous.setCatalogTimeout(Duration.ofSeconds(2));
        NewApiManagementClient anonymousClient = new NewApiManagementClient(
                anonymous,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());

        anonymousClient.fetchModelPlaza();

        assertEquals(0, logins.get());
        assertTrue(lastAuthorization.get() == null || lastAuthorization.get().isBlank());
    }

    private void encryptionKey(HttpExchange exchange) throws IOException {
        respond(exchange, """
                {"success": true, "data": {"enabled": false}}
                """);
    }

    private void login(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastPassword.set(objectMapper.readTree(body).path("password").asText(null));
        int attempt = logins.incrementAndGet();
        long expiresAt = System.currentTimeMillis() / 1000L + sessionLifetimeSeconds.get();
        respond(exchange, """
                {
                  "success": true,
                  "data": {
                    "access_token": "session-%d",
                    "access_expires_at": %d,
                    "user": {"id": 7, "username": "catalog-bot"}
                  }
                }
                """.formatted(attempt, expiresAt));
    }

    private void logout(HttpExchange exchange) throws IOException {
        logouts.incrementAndGet();
        respond(exchange, """
                {"success": true}
                """);
    }

    private void pricing(HttpExchange exchange) throws IOException {
        pricingCalls.incrementAndGet();
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        if (pricingRejectionsLeft.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            respondStatus(exchange, 401, """
                    {"success": false, "message": "unauthorized"}
                    """);
            return;
        }
        respond(exchange, """
                {
                  "success": true,
                  "group_ratio": {"default": 1},
                  "data": [
                    {
                      "model_name": "deepseek-chat",
                      "quota_type": 0,
                      "model_ratio": 0.08,
                      "completion_ratio": 4,
                      "enable_groups": ["default"],
                      "supported_endpoint_types": ["openai"]
                    }
                  ]
                }
                """);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respondStatus(exchange, 200, body);
    }

    private static void respondStatus(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

}
