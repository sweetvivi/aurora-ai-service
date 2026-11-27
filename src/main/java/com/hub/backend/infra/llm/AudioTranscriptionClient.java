package com.hub.backend.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** OpenAI-compatible multipart speech-to-text adapter used before agent chat completion. */
@Component
public class AudioTranscriptionClient {

    private static final int ERROR_BODY_LIMIT = 400;

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public AudioTranscriptionClient(HttpClient llmHttpClient,
                                    LlmProperties properties,
                                    ObjectMapper objectMapper) {
        this.httpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String transcribe(byte[] audio, String fileName, String mimeType,
                             ProviderCredentials credentials) {
        String model = properties.getAudioTranscriptionModel();
        if (model == null || model.isBlank()) {
            throw new LlmInvocationException(
                    "音频对话尚未配置转写模型，请设置 LLM_AUDIO_TRANSCRIPTION_MODEL");
        }
        if (credentials == null || !credentials.hasApiKey()) {
            throw new LlmInvocationException("音频转写需要账号模型凭据");
        }
        String boundary = "----CodexAudio" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, model, fileName, mimeType, audio);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(credentials)))
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + credentials.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body() == null ? "" : response.body().strip();
                if (detail.length() > ERROR_BODY_LIMIT) detail = detail.substring(0, ERROR_BODY_LIMIT);
                throw new LlmInvocationException(
                        "音频转写服务返回 HTTP %d%s".formatted(
                                response.statusCode(), detail.isEmpty() ? "" : ": " + detail));
            }
            JsonNode json = objectMapper.readTree(response.body());
            String text = json.path("text").asText("").strip();
            if (text.isEmpty()) throw new LlmInvocationException("音频转写结果为空");
            return text;
        } catch (IOException exception) {
            throw new LlmInvocationException("音频转写请求失败: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("音频转写请求已中断", exception);
        }
    }

    private String endpoint(ProviderCredentials credentials) {
        if (credentials.hasBaseUrl()) {
            String base = credentials.baseUrl();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String path = properties.getAudioTranscriptionsPath();
            if (path == null || path.isBlank()) return base;
            return path.startsWith("/") ? base + path : base + "/" + path;
        }
        return properties.resolvedAudioTranscriptionsUrl();
    }

    private static byte[] multipart(String boundary, String model, String fileName,
                                    String mimeType, byte[] audio) {
        String safeName = fileName == null ? "speech-audio" : fileName.replace("\"", "");
        String safeMime = mimeType == null ? "application/octet-stream" : mimeType;
        String prefix = "--%s\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n%s\r\n"
                .formatted(boundary, model)
                + "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                        .formatted(boundary, safeName)
                + "Content-Type: %s\r\n\r\n".formatted(safeMime);
        byte[] head = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + audio.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(audio, 0, body, head.length, audio.length);
        System.arraycopy(tail, 0, body, head.length + audio.length, tail.length);
        return body;
    }
}
