package com.hub.backend.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageEditFile;
import com.hub.backend.infra.llm.dto.ImageEditingCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.infra.llm.protocol.ChatProtocol;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI-compatible multipart adapter for {@code /images/edits}.
 */
@Component
public class OpenAiCompatibleImageEditingClient implements ImageEditingClient {

    private static final int ERROR_BODY_LIMIT = 500;
    private static final String MASK_FIELD = "mask";
    private static final String IMAGE_FIELD = "image";

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleImageEditingClient(HttpClient llmHttpClient,
                                              LlmProperties properties,
                                              ObjectMapper objectMapper) {
        this.httpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImageGenerationResult edit(ImageEditingCommand command) {
        if (command == null || command.images().isEmpty()
                || command.images().stream().anyMatch(image -> image.content().length == 0)) {
            throw new LlmInvocationException("Image editing requires a source image");
        }

        ProviderCredentials credentials = command.credentials();
        if (credentials != null) {
            ChatProtocol protocol = ChatProtocol.resolve(credentials.protocol());
            if (protocol != ChatProtocol.OPENAI && protocol != ChatProtocol.OPENAI_COMPATIBLE
                    && protocol != ChatProtocol.OLLAMA) {
                throw new LlmInvocationException(
                        "Image editing is only implemented for OpenAI-compatible suppliers, but this "
                                + "model's supplier speaks %s".formatted(protocol.label()));
            }
        }

        if (credentials == null || !credentials.hasApiKey()) {
            throw new LlmInvocationException(
                    "Image editing requires an account relay credential");
        }
        String apiKey = credentials.apiKey();

        String boundary = "----hub-" + UUID.randomUUID();
        HttpRequest request = buildRequest(command, apiKey, boundary);
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamError(response.statusCode(), response.body());
            }
            return parse(response.body());
        } catch (IOException exception) {
            throw new LlmInvocationException("Image editing call failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Image editing call was interrupted", exception);
        }
    }

    private HttpRequest buildRequest(ImageEditingCommand command, String apiKey, String boundary) {
        byte[] body = multipartBody(command, boundary);
        return HttpRequest.newBuilder(URI.create(imageEditsUrl(command.credentials())))
                .timeout(properties.getImageTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private byte[] multipartBody(ImageEditingCommand command, String boundary) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeText(out, boundary, "model", command.model());
            writeText(out, boundary, "prompt", command.prompt());
            // 同生成接口：auto 表示「交给上游决定」，不是上游认识的字面值，原样发会被当成
            // 「auto aspect」拒绝（400 auto aspect requires a Gemini image model ...）。
            if (command.size() != null && !command.size().isBlank()
                    && !"auto".equalsIgnoreCase(command.size().strip())) {
                writeText(out, boundary, "size", command.size().strip());
            }
            String imageField = command.images().size() == 1 ? IMAGE_FIELD : IMAGE_FIELD + "[]";
            for (ImageEditFile image : command.images()) {
                writeFile(out, boundary, imageField, image);
            }
            if (command.mask() != null && command.mask().content().length > 0) {
                writeFile(out, boundary, MASK_FIELD, command.mask());
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException exception) {
            throw new LlmInvocationException("Unable to build the image editing request", exception);
        }
    }

    private static void writeText(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(ByteArrayOutputStream out, String boundary, String name, ImageEditFile file)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        String fileName = file.fileName() == null || file.fileName().isBlank()
                ? "image.png"
                : file.fileName().replace("\"", "");
        String mimeType = file.mimeType() == null || file.mimeType().isBlank()
                ? "application/octet-stream"
                : file.mimeType();
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file.content());
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String imageEditsUrl(ProviderCredentials credentials) {
        String url = credentials != null && credentials.hasBaseUrl()
                ? supplierImagesUrl(credentials)
                : properties.resolvedImagesUrl();

        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (trimmed.endsWith("/images/edits")) {
            return trimmed;
        }
        if (trimmed.endsWith("/images/generations")) {
            return trimmed.substring(0, trimmed.length() - "/images/generations".length()) + "/images/edits";
        }
        if (trimmed.endsWith("/images")) {
            return trimmed + "/edits";
        }
        return trimmed;
    }

    /**
     * Image endpoint of a supplier that declares its own base URL.
     *
     * <p>A supplier without an explicit image path is assumed to serve edits at its own base. The
     * tempting alternative — falling back to {@code app.llm.images-path} on the configured default
     * host — would post this supplier's key to a different gateway.</p>
     */
    private static String supplierImagesUrl(ProviderCredentials credentials) {
        String base = credentials.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = credentials.imagesPath() == null ? "" : credentials.imagesPath().strip();
        if (path.isEmpty()) {
            return base + "/images/edits";
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    private ImageGenerationResult parse(String body) {
        if (body == null || body.isBlank()) {
            throw new LlmInvocationException("Image editing gateway returned an empty response body");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse the image editing gateway response", exception);
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
            throw new LlmInvocationException("Image editing gateway returned no usable image");
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
        // status 与上游 error.code 一并带上：handler 要据此决定回 400（用户可处理：内容审核、
        // 参数不合法）还是 502（真·网关故障）。塞在字符串里它只能一律 502。
        return new LlmInvocationException(
                "Image editing gateway returned HTTP %d: %s".formatted(statusCode, snippet),
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
