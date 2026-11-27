package com.hub.backend.infra.llm.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.UpstreamVideoStatus;
import com.hub.backend.infra.llm.dto.UpstreamVideoTask;
import com.hub.backend.infra.llm.dto.VideoGenerationCommand;
import com.hub.backend.infra.llm.protocol.ChatProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@link VideoGenerationClient} adapter for gateways that expose the OpenAI-compatible
 * asynchronous job shape.
 *
 * <p>Two calls: {@code POST {videosPath}} accepts the job and returns an id, and
 * {@code GET {videosPath}/{id}} reports its state. Fields are read defensively because relay
 * gateways differ on the exact nesting — the result URL is looked up at the documented path first
 * and then a few common alternatives, and job state is normalised through {@link #stateOf}.</p>
 *
 * <p>Auth is the caller's New API relay key, same as chat and image. Paths come from
 * {@code app.llm.videos-path} / {@code video-status-path}; New API task plugins own the upstream
 * hop.</p>
 */
@Component
public class OpenAiCompatibleVideoGenerationClient implements VideoGenerationClient {

    private static final int ERROR_BODY_LIMIT = 500;

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiCompatibleVideoGenerationClient.class);

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleVideoGenerationClient(HttpClient llmHttpClient,
                                                 LlmProperties properties,
                                                 ObjectMapper objectMapper) {
        this.httpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public UpstreamVideoTask submit(VideoGenerationCommand command) {
        requireOpenAiShape(command.credentials());
        String apiKey = apiKey(command.credentials());

        URI endpoint = URI.create(videosUrl(command.credentials()));
        // Logged because a relay answers a wrong path with an opaque Go "404 page not found":
        // without the URL in the log there is no way to tell an endpoint problem from a
        // permissions one.
        log.info("Submitting video task to {} (model {})", endpoint, command.model());

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.getVideoTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(command), StandardCharsets.UTF_8))
                .build();

        JsonNode root = send(request, "Video generation request");
        String taskId = firstText(root,
                // The demo-relay relay answers {"request_id": "..."} and nothing else; the OpenAI
                // shape uses id / task_id. All spellings are accepted so the same client works
                // against both without configuration.
                "request_id", "requestId", "id", "task_id", "data.id", "data.task_id",
                "data.taskId", "data.request_id", "output.id", "output.task_id");
        if (taskId == null) {
            // Some relays answer synchronously. There is no polling to do in that case, so the
            // result URL is reported as an already finished task id is impossible to express; fail
            // clearly rather than returning a task that can never finish.
            String immediate = firstText(root,
                    "data.url", "data.video_url", "data.videoUrl", "output.url", "url", "video_url");
            if (immediate != null) {
                throw new LlmInvocationException(
                        "Video gateway answered synchronously instead of creating a task; "
                                + "asynchronous task ids are required by this endpoint");
            }
            throw new LlmInvocationException("Video gateway returned no task id");
        }
        return new UpstreamVideoTask(taskId);
    }

    @Override
    public UpstreamVideoStatus poll(ProviderCredentials credentials, String upstreamTaskId) {
        if (upstreamTaskId == null || upstreamTaskId.isBlank()) {
            throw new LlmInvocationException("Upstream video task id is missing");
        }
        requireOpenAiShape(credentials);
        String apiKey = apiKey(credentials);

        JsonNode root = sendStatus(credentials, apiKey, upstreamTaskId);
        UpstreamVideoStatus.State state = stateOf(root);
        int progress = progressOf(root);

        return switch (state) {
            case SUCCEEDED -> {
                String videoUrl = firstText(root,
                        // demo-relay relay: {"video":{"url":"/v1/videos/{id}/content"}}
                        "video.url", "video_url", "videoUrl",
                        "data.url", "data.video_url", "data.videoUrl", "data.output.url",
                        "output.url", "output.video_url", "url", "result.url");
                if (videoUrl == null) {
                    // OpenAI's Videos API keeps the bytes on a separate content route and the
                    // metadata carries no URL at all: {"id":"task_...","object":"video",
                    // "status":"completed","progress":100}. Failing here marked a finished (and
                    // already billed) video as "succeeded without a result URL", so the task page
                    // spun forever. The content route is derivable from the task id.
                    yield UpstreamVideoStatus.succeeded(
                            upstreamTaskId, contentUrl(credentials, upstreamTaskId));
                }
                // The relay answers with a path relative to its own origin
                // ("/v1/videos/{id}/content"). Resolving it here keeps the downloader's absolute
                // URL rule intact instead of teaching it about gateway base paths.
                yield UpstreamVideoStatus.succeeded(upstreamTaskId, absoluteUrl(credentials, videoUrl));
            }
            case FAILED -> UpstreamVideoStatus.failed(upstreamTaskId,
                    firstText(root, "error.message", "error", "data.error.message", "data.error",
                            "message", "fail_reason"));
            case RUNNING -> UpstreamVideoStatus.running(upstreamTaskId, progress);
            case QUEUED -> UpstreamVideoStatus.queued(upstreamTaskId, progress);
        };
    }

    private void requireOpenAiShape(ProviderCredentials credentials) {
        if (credentials == null) {
            return;
        }
        ChatProtocol protocol = ChatProtocol.resolve(credentials.protocol());
        if (protocol != ChatProtocol.OPENAI && protocol != ChatProtocol.OPENAI_COMPATIBLE
                && protocol != ChatProtocol.OLLAMA) {
            throw new LlmInvocationException(
                    "Video generation is only implemented for OpenAI-compatible suppliers, but this "
                            + "model's supplier speaks %s".formatted(protocol.label()));
        }
    }

    private String apiKey(ProviderCredentials credentials) {
        // Same as chat/image: the service layer hands in the caller's New API relay key.
        if (credentials == null || !credentials.hasApiKey()) {
            throw new LlmInvocationException(
                    "Video generation requires the caller's New API relay key");
        }
        return credentials.apiKey();
    }

    private String body(VideoGenerationCommand command) {
        try {
            return objectMapper.writeValueAsString(videoPayload(command));
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to serialise the video request", exception);
        }
    }

    /**
     * One request body that every video protocol reads correctly.
     *
     * <p>New API picks a task plugin by <em>channel type</em>, and each plugin reads a different
     * vocabulary from the same JSON — so the client cannot know which one will answer and must not
     * guess from the model name:</p>
     *
     * <ul>
     *   <li><b>xAI</b> (grok): {@code image} / {@code reference_images} / {@code last_frame}, as
     *       {@code {url}} objects or plain URL strings. It copies every request key into the
     *       upstream body, so extra fields are harmless — but a lone {@code images[0]} would be
     *       promoted to a first frame, which is why {@code images[]} is never sent.</li>
     *   <li><b>doubao / Ark</b> (seedance): the native {@code content[]} array, where each image
     *       carries a {@code role}. A body carrying {@code content} is passed through verbatim,
     *       which is the only way to say "this is a reference image, not the opening frame".</li>
     *   <li><b>sora</b>: reads {@code image} / {@code input_reference} as a URL string.</li>
     * </ul>
     *
     * <p>So a first frame goes out as {@code image} (a string — xAI and sora both take it) plus a
     * {@code content} entry with role {@code first_frame}; reference images go out as
     * {@code reference_images} (xAI), {@code image_urls} (openai.fit shorthand) and {@code content}
     * entries with role {@code reference_image} — never as a first-frame field, because xAI's task
     * plugin promotes {@code input_reference} to {@code image} and the clip would start from the
     * reference. Nothing is chosen by model name, so a channel that moves to another plugin keeps
     * working.</p>
     */
    private Map<String, Object> videoPayload(VideoGenerationCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("prompt", command.prompt());
        if (command.aspectRatio() != null && !command.aspectRatio().isBlank()) {
            // xAI 叫 aspect_ratio，Ark 叫 ratio；两边都不认识对方的键，各自忽略即可。
            payload.put("aspect_ratio", command.aspectRatio());
            payload.put("ratio", command.aspectRatio());
        }
        if (command.resolution() != null && !command.resolution().isBlank()) {
            payload.put("resolution", command.resolution());
        }

        List<Map<String, Object>> content = new ArrayList<>();
        List<String> references = new ArrayList<>();
        if (command.referenceImages() != null) {
            command.referenceImages().stream()
                    .filter(url -> url != null && !url.isBlank())
                    .map(this::publicMediaUrl)
                    .forEach(references::add);
        }
        String firstFrame = publicMediaUrl(command.referenceImage());
        String lastFrame = publicMediaUrl(command.lastFrameImage());

        if (!references.isEmpty()) {
            // 参考模式：图片是素材而不是首帧。xAI 只认 reference_images，Ark 类插件只认 role，
            // openai.fit 的简写是 image_urls —— 三份都发，各插件读自己认识的那个。
            payload.put("reference_images", references.stream()
                    .map(OpenAiCompatibleVideoGenerationClient::mediaUrl)
                    .toList());
            payload.put("image_urls", references);
            // input_reference 在 OpenAI Videos 契约里是「从哪一帧开始」，不是参考图：xAI 任务插件
            // 会把它原样提升成 image（source.image ??= source.input_reference），于是参考图变成
            // 开场画面。默认不发 —— reference_images / image_urls / content[role] 已经够表达素材。
            // 只有所在通道的插件确实只认它、会把另外几份丢掉时，才在该模型的名单里打开。
            if (requiresInputReference(command.model())) {
                payload.put("input_reference", references.get(0));
            }
            content.add(arkText(command.prompt()));
            for (String url : references) {
                content.add(arkImage(url, "reference_image"));
            }
        } else if (firstFrame != null && !firstFrame.isBlank()) {
            // 首帧模式：xAI 和 sora 都认字符串 image，Ark 类插件用 role=first_frame。
            payload.put("image", firstFrame);
            payload.put("image_url", firstFrame);
            payload.put("input_reference", firstFrame);
            content.add(arkText(command.prompt()));
            content.add(arkImage(firstFrame, "first_frame"));
        }
        if (lastFrame != null && !lastFrame.isBlank()) {
            payload.put("last_frame", mediaUrl(lastFrame));
            payload.put("last_frame_image", lastFrame);
            payload.put("tail_image_url", lastFrame);
            if (!content.isEmpty()) {
                content.add(arkImage(lastFrame, "last_frame"));
            }
        }

        if (command.durationSeconds() != null) {
            // 只用 seconds，且必须是字符串：OpenAI Videos 把它定义成 string（sora / oai_videos 按
            // Go 的 string 反序列化），xAI 两种都收。不发 duration —— xAI 只要同时看到
            // duration 和 seconds 就 422，而 Ark 那条路（content[] 透传）目前在上游走不通。
            payload.put("seconds", String.valueOf(command.durationSeconds()));
        }
        if (!content.isEmpty()) {
            // content 是给 Ark 类插件的原生形状（能带图片 role）；sora / oai_videos 会按白名单
            // 把它丢掉，靠上面的 image / input_reference 取图，两边互不干扰。
            payload.put("content", content);
        }
        return payload;
    }

    /**
     * 把内联 {@code data:} 素材换成公网链接（配置了素材上传地址时）。
     *
     * <p>供应商收参考图基本只认 {@code https} 链接：data URL 会被忽略或直接拒收，任务于是静默
     * 退化成文生视频。上传后图片进了供应商自己的存储，请求体里只留一个链接。</p>
     *
     * @param media https 链接、data URL 或 {@code null}
     * @return 真正要发出去的形态；已经是链接或没配置上传地址时原样返回
     */
    String publicMediaUrl(String media) {
        if (media == null || media.isBlank() || !media.startsWith("data:")) {
            return media;
        }
        String endpoint = properties.resolvedVideoUploadUrl();
        if (endpoint == null) {
            return media;
        }
        int comma = media.indexOf(',');
        if (comma < 0) {
            throw new LlmInvocationException("Inline video reference is not a valid data URL");
        }
        String meta = media.substring("data:".length(), comma);
        String encoded = media.substring(comma + 1);
        String mimeType = meta.split(";")[0].isBlank() ? "application/octet-stream" : meta.split(";")[0];
        byte[] bytes;
        if (meta.toLowerCase(Locale.ROOT).contains("base64")) {
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new LlmInvocationException("Inline video reference is not valid base64", exception);
            }
        } else {
            bytes = encoded.getBytes(StandardCharsets.UTF_8);
        }
        return uploadMedia(endpoint, bytes, mimeType);
    }

    private String uploadMedia(String endpoint, byte[] bytes, String mimeType) {
        String boundary = "----hub" + UUID.randomUUID().toString().replace("-", "");
        String extension = mimeType.contains("/") ? mimeType.substring(mimeType.indexOf('/') + 1) : "bin";
        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes.length + 256);
        String lineBreak = "\r\n";
        String head = "--" + boundary + lineBreak
                + "Content-Disposition: form-data; name=\"file\"; filename=\"reference."
                + extension + "\"" + lineBreak
                + "Content-Type: " + mimeType + lineBreak + lineBreak;
        try {
            body.write(head.getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write((lineBreak + "--" + boundary + "--" + lineBreak).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new LlmInvocationException("Unable to prepare the video reference upload", exception);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(properties.getVideoTimeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        String uploadKey = properties.getVideoUploadApiKey();
        if (uploadKey != null && !uploadKey.isBlank()) {
            builder.header("Authorization", "Bearer " + uploadKey.trim());
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmInvocationException(
                        "Video reference upload returned HTTP %d".formatted(response.statusCode()));
            }
            String url = objectMapper.readTree(response.body()).path("url").asText("");
            if (url.isBlank()) {
                throw new LlmInvocationException("Video reference upload returned no url");
            }
            return url;
        } catch (IOException exception) {
            throw new LlmInvocationException(
                    "Video reference upload failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Video reference upload was interrupted", exception);
        }
    }

    /**
     * Whether this model's channel keeps nothing but {@code input_reference}.
     *
     * <p>sora / {@code oai_videos} rebuild the upstream body from a whitelist and drop
     * {@code reference_images}, {@code image_urls} and {@code content[]}, so a reference sent only
     * those ways never arrives. Listing such a model makes its references ride on the one field the
     * channel does keep — at the cost that the upstream then treats the picture as the opening
     * frame, because that is all {@code input_reference} means.</p>
     *
     * <p>Empty by default: a reference-mode request must never carry a first-frame field or xAI
     * silently turns it into a first-frame request.</p>
     */
    private boolean requiresInputReference(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        List<String> configured = properties.getVideoInputReferenceModels();
        if (configured == null || configured.isEmpty()) {
            return false;
        }
        String key = model.strip();
        return configured.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .map(String::strip)
                .anyMatch(key::equalsIgnoreCase);
    }

    private static Map<String, Object> arkText(String prompt) {
        return Map.of("type", "text", "text", prompt);
    }

    private static Map<String, Object> arkImage(String url, String role) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "image_url");
        item.put("image_url", Map.of("url", url));
        item.put("role", role);
        return item;
    }

    /**
     * xAI video media input: {@code {"url":"data:image/...;base64,..."}} (or an https URL).
     *
     * <p>Package-visible for unit tests.</p>
     */
    static Map<String, String> mediaUrl(String url) {
        return Map.of("url", url);
    }

    /** Package-visible so tests can assert the JSON shape without standing up an HTTP server. */
    String requestBody(VideoGenerationCommand command) {
        return body(command);
    }

    private String videosUrl(ProviderCredentials credentials) {
        return joinUrl(baseUrl(credentials), path(credentials, properties.getVideosPath()));
    }

    /**
     * Bytes route for a finished job: {@code {videosUrl}/{id}/content}, the OpenAI Videos contract.
     *
     * <p>Used when the status payload names no URL — which is exactly what the sora task plugin
     * answers with — so a finished video is still downloadable instead of being reported as a
     * failure with no result.</p>
     */
    private String contentUrl(ProviderCredentials credentials, String taskId) {
        return videosUrl(credentials) + "/" + taskId + "/content";
    }

    private String statusUrl(ProviderCredentials credentials, String upstreamTaskId) {
        String configured = credentials != null && credentials.hasVideoStatusPath()
                ? credentials.videoStatusPath()
                : properties.getVideoStatusPath();
        String base;
        String path;
        if (configured != null && isHttpPath(configured.strip())) {
            // Status may be served on a different route than submit. The origin rather than the
            // base URL is the anchor: a path like /xai/v1/videos already carries the /v1 segment,
            // and appending it to a base that ends in /v1 would double it.
            base = origin(baseUrl(credentials));
            path = configured.strip();
        } else {
            base = videosUrl(credentials);
            path = "";
        }
        String full = joinUrl(base, path);
        return full.endsWith("/") ? full + upstreamTaskId : full + "/" + upstreamTaskId;
    }

    /** Strips a trailing {@code /v1} so a path that already contains it is not doubled. */
    private static String origin(String base) {
        return base.endsWith("/v1") ? base.substring(0, base.length() - 3) : base;
    }

    private String baseUrl(ProviderCredentials credentials) {
        if (credentials == null || !credentials.hasBaseUrl()) {
            return properties.resolvedBaseUrl();
        }
        String base = credentials.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * @return the URL as given when absolute, otherwise resolved against the configured result
     *         origin — the base URL unless {@code app.llm.video-result-base-url} names another one.
     *         {@code /v1/videos/{id}/content} is relative to that origin, which already ends in
     *         {@code /v1}, so that prefix is not duplicated.
     */
    private String absoluteUrl(ProviderCredentials credentials, String url) {
        String value = url.strip();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        String base;
        if (credentials != null && credentials.hasVideoResultBaseUrl()) {
            base = trimTrailingSlash(credentials.videoResultBaseUrl());
        } else if (isAbsolute(properties.getVideoResultBaseUrl())) {
            base = properties.resolvedVideoResultBaseUrl();
        } else {
            base = baseUrl(credentials);
        }
        if (value.startsWith("/v1/") && base.endsWith("/v1")) {
            return base.substring(0, base.length() - 3) + value;
        }
        return value.startsWith("/") ? base + value : base + "/" + value;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isAbsolute(String url) {
        if (url == null) {
            return false;
        }
        String value = url.strip();
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private String path(ProviderCredentials credentials, String fallback) {
        if (credentials != null && credentials.hasVideosPath()) {
            String candidate = credentials.videosPath().strip();
            if (isHttpPath(candidate)) {
                return candidate;
            }
        }
        String configured = fallback == null ? "" : fallback.strip();
        return isHttpPath(configured) ? configured : "";
    }

    /**
     * Rejects anything that cannot be a path on the gateway.
     *
     * <p>Git Bash rewrites a leading slash in an environment variable into a Windows path, so
     * {@code LLM_VIDEO_PATH=/videos/generations} arrives as
     * {@code D:/BaiduNetdiskDownload/Git/videos/generations}. Concatenating that yields
     * {@code /v1/D:/.../videos/generations}, which the relay answers with a bare
     * "404 page not found". An absolute path on the gateway always starts with {@code /} and has no
     * colon; anything else falls back to the compiled default rather than building a broken URL.</p>
     */
    private static boolean isHttpPath(String candidate) {
        return candidate.startsWith("/") && candidate.indexOf(':') < 0;
    }

    private static String joinUrl(String base, String path) {
        if (path == null || path.isEmpty()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    /**
     * 读任务状态；配置的查询路径回 404 时，退回提交用的路径再查一次。
     *
     * <p>{@code app.llm.video-status-path} 默认是 {@code /xai/v1/videos}，那是为 xAI 任务插件
     * 准备的。同一站点上按 OpenAI Videos 形状提交（{@code /videos}）的任务，在 {@code /xai}
     * 路由下查不到，会回 {@code {"code":"task_not_found"}}：任务其实在跑、进度一直在涨，
     * 我们却每 5 秒记一次 404，直到超时把已经付费的视频判成失败。实测 demo-relay 就是这种情况
     * （{@code /xai/v1/videos/{id}} → 404，{@code /v1/videos/{id}} → 200 completed）。</p>
     */
    private JsonNode sendStatus(ProviderCredentials credentials, String apiKey, String taskId) {
        String primary = statusUrl(credentials, taskId);
        try {
            return send(statusRequest(primary, apiKey), "Video task status");
        } catch (LlmInvocationException failure) {
            String fallback = videosUrl(credentials) + "/" + taskId;
            if (fallback.equals(primary) || !isNotFound(failure)) {
                throw failure;
            }
            return send(statusRequest(fallback, apiKey), "Video task status");
        }
    }

    private static boolean isNotFound(LlmInvocationException failure) {
        String message = failure.getMessage();
        return message != null && message.contains("HTTP 404");
    }

    private HttpRequest statusRequest(String url, String apiKey) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.getVideoTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private JsonNode send(HttpRequest request, String label) {        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 202 Accepted is a normal answer for a job that is still running: the relay uses it
            // together with a {"status":"pending","progress":25} body.
            boolean accepted = response.statusCode() == 202;
            if (!accepted && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                throw upstreamError(label, response.statusCode(), response.body());
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new LlmInvocationException(label + " returned an empty response body");
            }
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException(label + " returned unparseable JSON", exception);
        } catch (IOException exception) {
            throw new LlmInvocationException("Video gateway call failed: " + exception.getMessage(),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Video gateway call was interrupted", exception);
        }
    }

    private static LlmInvocationException upstreamError(String label, int statusCode, String body) {
        String snippet = body == null ? "" : body.strip();
        if (snippet.length() > ERROR_BODY_LIMIT) {
            snippet = snippet.substring(0, ERROR_BODY_LIMIT) + "...";
        }
        return new LlmInvocationException(
                "%s returned HTTP %d: %s".formatted(label, statusCode, snippet));
    }

    /**
     * Normalises the many vocabularies relays use. The status is read from the documented fields
     * first; anything unrecognised is treated as still running, so a wording change slows the poll
     * down instead of failing a job that is actually fine.
     */
    private static UpstreamVideoStatus.State stateOf(JsonNode root) {
        String raw = firstText(root,
                "status", "state", "data.status", "data.state", "output.status", "task.status");
        if (raw == null) {
            // An explicit result URL is the strongest signal of completion.
            return firstText(root, "video.url", "data.url", "video_url", "url", "video_url",
                    "output.url") != null
                    ? UpstreamVideoStatus.State.SUCCEEDED
                    : UpstreamVideoStatus.State.RUNNING;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "queued", "pending", "waiting", "created", "submitted" ->
                    UpstreamVideoStatus.State.QUEUED;
            // "done" is what the demo-relay relay reports; the rest come from other compatible
            // suppliers. See docs/video-generation-api.md for the captured payloads.
            case "succeeded", "success", "completed", "complete", "done", "finished" ->
                    UpstreamVideoStatus.State.SUCCEEDED;
            case "failed", "failure", "error", "cancelled", "canceled", "rejected", "expired" ->
                    UpstreamVideoStatus.State.FAILED;
            default -> UpstreamVideoStatus.State.RUNNING;
        };
    }

    private static int progressOf(JsonNode root) {
        for (String path : new String[]{"progress", "data.progress", "output.progress", "percent"}) {
            JsonNode node = at(root, path);
            if (node != null && (node.isInt() || node.isDouble() || node.isLong())) {
                int value = (int) Math.round(node.asDouble());
                return Math.max(0, Math.min(100, value));
            }
            if (node != null && node.isTextual()) {
                try {
                    return Math.max(0, Math.min(100, (int) Math.round(Double.parseDouble(node.asText()))));
                } catch (NumberFormatException ignored) {
                    // fall through to the next candidate
                }
            }
        }
        return -1;
    }

    /** @return the first non-blank textual value found at any of the dotted paths */
    private static String firstText(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = at(root, path);
            if (node != null && node.isValueNode() && !node.isNull()) {
                String value = node.asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static JsonNode at(JsonNode root, String dottedPath) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.path(segment);
            if (current.isMissingNode()) {
                return null;
            }
        }
        return current;
    }
}
