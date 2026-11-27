package com.hub.backend.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.UpstreamModel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link UpstreamModelCatalogClient} adapter for OpenAI-compatible gateways ({@code GET /models}).
 */
@Component
public class OpenAiCompatibleModelCatalogClient implements UpstreamModelCatalogClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleModelCatalogClient(HttpClient llmHttpClient,
                                              LlmProperties properties,
                                              ObjectMapper objectMapper) {
        this.httpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<UpstreamModel> listModels(ProviderCredentials credentials) {
        String apiKey = credentials != null && credentials.hasApiKey()
                ? credentials.apiKey()
                : properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmInvocationException(
                    "No key is available for this supplier, store one on the provider or send it in the request");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(modelsUrl(credentials)))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamError(response.statusCode(), response.body());
            }
            return parse(response.body());
        } catch (IOException exception) {
            throw new LlmInvocationException(
                    "Model list call failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Model list call was interrupted", exception);
        }
    }

    private String modelsUrl(ProviderCredentials credentials) {
        String base = credentials != null && credentials.hasBaseUrl()
                ? credentials.baseUrl()
                : properties.resolvedBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = properties.getModelsPath() == null ? "" : properties.getModelsPath().strip();
        if (path.isEmpty()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    private List<UpstreamModel> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new LlmInvocationException("Model list response was empty");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse the model list response", exception);
        }

        List<UpstreamModel> models = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            String id = textOrNull(item.path("id"));
            if (id == null) {
                continue;
            }
            String ownedBy = textOrNull(item.path("owned_by"));
            JsonNode created = item.path("created");
            models.add(new UpstreamModel(id, ownedBy, created.isNumber() ? created.asLong() : null));
        }
        return List.copyOf(models);
    }

    private static LlmInvocationException upstreamError(int statusCode, String body) {
        String snippet = body == null ? "" : body.strip();
        if (snippet.length() > ERROR_BODY_LIMIT) {
            snippet = snippet.substring(0, ERROR_BODY_LIMIT) + "...";
        }
        return new LlmInvocationException(
                "Model list request returned HTTP %d: %s".formatted(statusCode, snippet));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isEmpty() ? null : value;
    }
}
