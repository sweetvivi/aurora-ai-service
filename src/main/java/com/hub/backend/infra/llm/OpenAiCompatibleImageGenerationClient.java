package com.hub.backend.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageGenerationCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.infra.llm.protocol.ChatProtocol;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ImageGenerationClient} adapter for OpenAI-compatible gateways.
 *
 * <p>Uses the image credentials rather than the chat ones: on the current gateway the two belong to
 * different groups, and a chat key sent here comes back as a confusing "model not available for this
 * group" instead of an authentication error.</p>
 */
@Component
public class OpenAiCompatibleImageGenerationClient implements ImageGenerationClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleImageGenerationClient(HttpClient llmHttpClient,
                                                 LlmProperties properties,
                                                 ObjectMapper objectMapper) {
        this.httpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImageGenerationResult generate(ImageGenerationCommand command) {
        ProviderCredentials credentials = command.credentials();
        if (credentials != null) {
            // Image generation is only wired for the OpenAI shape. Saying so beats sending an OpenAI
            // body to a supplier that expects something else.
            ChatProtocol protocol = ChatProtocol.resolve(credentials.protocol());
            if (protocol != ChatProtocol.OPENAI && protocol != ChatProtocol.OPENAI_COMPATIBLE
                    && protocol != ChatProtocol.OLLAMA) {
                throw new LlmInvocationException(
                        "Image generation is only implemented for OpenAI-compatible suppliers, but this "
                                + "model's supplier speaks %s".formatted(protocol.label()));
            }
        }

        if (credentials == null || !credentials.hasApiKey()) {
            throw new LlmInvocationException(
                    "Image generation requires an account relay credential");
        }
        String apiKey = credentials.apiKey();

        HttpRequest request = buildRequest(command, apiKey);
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamError(response.statusCode(), response.body());
            }
            return parse(response.body());
        } catch (IOException exception) {
            throw new LlmInvocationException("Image generation call failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Image generation call was interrupted", exception);
        }
    }

    private HttpRequest buildRequest(ImageGenerationCommand command, String apiKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("prompt", command.prompt());
        payload.put("n", command.count());
        // 「auto」是我们接口里的「交给上游决定」，不是一个上游认识的字面值。
        // 原样发出去会被网关当成「auto aspect」拒掉：
        //   400 auto aspect requires a Gemini image model and reference images
        // 所以 auto 一律不传这个字段，让上游用自己的默认值。
        // 「auto」是我们接口里的「交给上游决定」，不是一个上游认识的字面值。原样发出去会被
        // 网关当成具体含义拒掉（实测 api.demo-relay.online）：
        //   size=auto    → 400 auto aspect requires a Gemini image model and reference images
        //   quality=auto → 400 unsupported quality; use an explicit resolution
        // 所以 auto 一律不传这个字段，让上游用自己的默认值；真实值照常透传
        // （quality=high 不带 size 也是 200，不必额外约束两者共存）。
        putIfExplicit(payload, "size", command.size());
        putIfExplicit(payload, "quality", command.quality());

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to serialise the image request", exception);
        }

        return HttpRequest.newBuilder(URI.create(imagesUrl(command.credentials())))
                .timeout(properties.getImageTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 只在调用方真的指定了值时才写进请求体。
     *
     * @return 是否写入了字段（调用方据此判断「有没有显式指定」）
     */
    private static boolean putIfExplicit(Map<String, Object> payload, String field, String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty() || "auto".equalsIgnoreCase(trimmed)) {
            return false;
        }
        payload.put(field, trimmed);
        return true;
    }

    private String imagesUrl(ProviderCredentials credentials) {
        if (credentials == null || !credentials.hasBaseUrl()) {
            return properties.resolvedImagesUrl();
        }
        String base = credentials.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = credentials.imagesPath() == null ? "" : credentials.imagesPath().strip();
        if (path.isEmpty()) {
            // Fall back to the supplier's own host, not the configured default: sending this
            // supplier's key to another gateway would both fail and leak the credential.
            return base + "/images/generations";
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    private ImageGenerationResult parse(String body) {
        if (body == null || body.isBlank()) {
            throw new LlmInvocationException("Image gateway returned an empty response body");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse the image gateway response", exception);
        }

        List<GeneratedImage> images = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            GeneratedImage image = new GeneratedImage(
                    textOrNull(item.path("b64_json")),
                    textOrNull(item.path("url")),
                    textOrNull(item.path("revised_prompt")));
            if (image.hasInlineData() || image.hasUrl()) {
                images.add(image);
            }
        }

        if (images.isEmpty()) {
            throw new LlmInvocationException("Image gateway returned no usable image");
        }

        long created = root.path("created").asLong(Instant.now().getEpochSecond());
        String model = textOrNull(root.path("model"));
        return new ImageGenerationResult(created, model == null ? null : model, images);
    }

    private static LlmInvocationException upstreamError(int statusCode, String body) {
        String snippet = body == null ? "" : body.strip();
        if (snippet.length() > ERROR_BODY_LIMIT) {
            snippet = snippet.substring(0, ERROR_BODY_LIMIT) + "...";
        }
        return new LlmInvocationException(
                "Image gateway returned HTTP %d: %s".formatted(statusCode, snippet),
                statusCode,
                UpstreamFailureText.upstreamCode(body));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isEmpty() ? null : value;
    }
}
