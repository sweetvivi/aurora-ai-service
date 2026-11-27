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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The catalogue is read from {@code /api/pricing}: the deployed New API has no model-plaza route,
 * and {@code /api/models/public} is really {@code /api/models/:id} behind admin auth.
 */
class NewApiManagementClientCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();

    private HttpServer server;
    private NewApiManagementClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/pricing", this::pricing);
        server.start();

        NewApiProperties properties = new NewApiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCatalogTimeout(Duration.ofSeconds(2));
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
    void readsTheAnonymousPricingCatalogueWithoutCredentials() {
        NewApiModelPlaza plaza = client.fetchModelPlaza();

        assertEquals("/api/pricing", requestPath.get());
        assertEquals("GET", requestMethod.get());
        assertNull(authorization.get());

        Map<String, List<NewApiModelPlaza.Model>> byGroup = plaza.groups().stream()
                .collect(Collectors.toMap(
                        NewApiModelPlaza.Group::name,
                        NewApiModelPlaza.Group::models));
        assertEquals(2, byGroup.size());
        // default carries every advertised model, vip only the one that lists it.
        assertEquals(4, byGroup.get("default").size());
        assertEquals(1, byGroup.get("vip").size());
    }

    @Test
    void convertsTokenRatiosToUsdPerToken() {
        NewApiModelPlaza.Pricing pricing = pricingOf("deepseek-chat", "default");

        assertNull(pricing.billingMode());
        // 0.08 ratio / 500000 quota-per-unit = 0.00000016 USD per token
        assertDecimal("0.00000016", pricing.inputPrice());
        assertDecimal("0.00000064", pricing.outputPrice());
        assertDecimal("0.0000000032", pricing.cacheReadPrice());
        assertNull(pricing.perRequestPrice());
        assertEquals(0, pricing.intervals().size());
    }

    @Test
    void convertsPerRequestPriceAndAppliesTheGroupRatio() {
        assertDecimal("0.3", pricingOf("grok-video", "default").perRequestPrice());
        // vip group ratio is 2 in the fixture
        assertDecimal("0.6", pricingOf("grok-video", "vip").perRequestPrice());
    }

    @Test
    void readsTierExpressionCoefficientsAsUsdPerToken() {
        NewApiModelPlaza.Pricing pricing = pricingOf("gpt-image", "default");

        assertEquals("tiered_expr", pricing.billingMode());
        // tier("standard", p * 5 + c * 30): the coefficients are USD per million tokens.
        assertDecimal("0.000005", pricing.inputPrice());
        assertDecimal("0.00003", pricing.outputPrice());
        assertNull(pricing.perRequestPrice());
        assertEquals(0, pricing.intervals().size());
    }

    @Test
    void turnsContextLengthConditionsIntoPriceIntervals() {
        NewApiModelPlaza.Pricing pricing = pricingOf("gpt-6", "default");

        assertEquals("tiered_expr", pricing.billingMode());
        // The headline price is the cheapest-context tier.
        assertDecimal("0.0000025", pricing.inputPrice());
        assertDecimal("0.000015", pricing.outputPrice());

        assertEquals(2, pricing.intervals().size());
        NewApiModelPlaza.Interval standard = pricing.intervals().get(0);
        assertEquals(0L, standard.minTokens());
        assertEquals(272_000L, standard.maxTokens());
        assertDecimal("0.0000025", standard.inputPrice());
        assertDecimal("0.00000025", standard.cacheReadPrice());

        NewApiModelPlaza.Interval longContext = pricing.intervals().get(1);
        assertEquals(272_001L, longContext.minTokens());
        assertNull(longContext.maxTokens());
        assertDecimal("0.000005", longContext.inputPrice());
        assertDecimal("0.0000225", longContext.outputPrice());
    }

    @Test
    void keepsTheFirstEndpointTypeAsThePlatformHint() {
        assertEquals("image-generation", pricingPlatformOf("gpt-image", "default"));
    }

    @Test
    void readsTheSelectableGroupsAndEveryGroupAModelIsServedIn() {
        NewApiModelPlaza plaza = client.fetchModelPlaza();

        Map<String, NewApiModelPlaza.GroupInfo> groups = plaza.availableGroups().stream()
                .collect(Collectors.toMap(NewApiModelPlaza.GroupInfo::name, info -> info));
        assertEquals(2, groups.size());
        assertEquals("VIP group", groups.get("vip").label());
        assertDecimal("2", groups.get("vip").ratio());
        assertEquals("Default group", groups.get("default").label());

        // The array is the model's, not the caller's view: it lists both groups even though the
        // per-group fan-out below duplicates the row.
        assertEquals(List.of("default", "vip"), model("grok-video", "default").enableGroups());
        assertEquals(List.of("default"), model("deepseek-chat", "default").enableGroups());
    }

    private NewApiModelPlaza.Pricing pricingOf(String modelName, String groupName) {
        NewApiModelPlaza.Model model = model(modelName, groupName);
        assertNotNull(model.pricing(), "expected a pricing entry for " + modelName);
        return model.pricing();
    }

    private String pricingPlatformOf(String modelName, String groupName) {
        return model(modelName, groupName).platform();
    }

    private NewApiModelPlaza.Model model(String modelName, String groupName) {
        return client.fetchModelPlaza().groups().stream()
                .filter(group -> groupName.equals(group.name()))
                .flatMap(group -> group.models().stream())
                .filter(model -> modelName.equals(model.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        modelName + " missing from group " + groupName));
    }

    private void pricing(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestPath.set(exchange.getRequestURI().getPath());
        requestMethod.set(exchange.getRequestMethod());
        respond(exchange, """
                {
                  "success": true,
                  "group_ratio": {"default": 1, "vip": 2},
                  "usable_group": {"default": "Default group", "vip": "VIP group"},
                  "data": [
                    {
                      "model_name": "deepseek-chat",
                      "quota_type": 0,
                      "model_ratio": 0.08,
                      "model_price": 0,
                      "completion_ratio": 4,
                      "cache_ratio": 0.02,
                      "enable_groups": ["default"],
                      "supported_endpoint_types": ["openai"]
                    },
                    {
                      "model_name": "grok-video",
                      "quota_type": 1,
                      "model_ratio": 0,
                      "model_price": 0.3,
                      "completion_ratio": 0,
                      "enable_groups": ["default", "vip"],
                      "supported_endpoint_types": ["image-generation"]
                    },
                    {
                      "model_name": "gpt-image",
                      "quota_type": 0,
                      "model_ratio": 37.5,
                      "model_price": 0,
                      "completion_ratio": 2,
                      "billing_mode": "tiered_expr",
                      "billing_expr": "tier(\\"standard\\", p * 5 + c * 30)",
                      "enable_groups": ["default"],
                      "supported_endpoint_types": ["image-generation", "openai"]
                    },
                    {
                      "model_name": "gpt-6",
                      "quota_type": 0,
                      "model_ratio": 37.5,
                      "model_price": 0,
                      "completion_ratio": 2,
                      "billing_mode": "tiered_expr",
                      "billing_expr": "len <= 272000 ? tier(\\"standard\\", p * 2.5 + c * 15 + cr * 0.25) : tier(\\"long_context\\", p * 5 + c * 22.5 + cr * 0.5)",
                      "enable_groups": ["default"],
                      "supported_endpoint_types": ["openai"]
                    }
                  ]
                }
                """);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual, "expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
