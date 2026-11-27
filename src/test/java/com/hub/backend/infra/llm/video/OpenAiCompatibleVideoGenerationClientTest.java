package com.hub.backend.infra.llm.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.UpstreamVideoStatus;
import com.hub.backend.infra.llm.dto.VideoGenerationCommand;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the upstream JSON shape for image / reference inputs.
 *
 * <p>xAI (and the New API xAI plugin) expect {@code image}/{@code reference_images}/{@code last_frame}
 * as {@code {url: "..."}} objects. A bare data-URL string is often ignored, so the job quietly
 * becomes text-to-video — which looks like "参考图没有上传过去".</p>
 */
class OpenAiCompatibleVideoGenerationClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiCompatibleVideoGenerationClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatibleVideoGenerationClient(
                HttpClient.newHttpClient(), new LlmProperties(), objectMapper);
    }

    @Test
    void wrapsImageToVideoAsUrlObject() throws Exception {
        String dataUrl = "data:image/png;base64,aaa";
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "grok-imagine-video",
                "animate this",
                "image2video",
                "16:9",
                "720p",
                5,
                dataUrl,
                null,
                List.of(),
                null)));

        assertEquals(dataUrl, root.path("image").asText());
        assertEquals(dataUrl, root.path("image_url").asText());
        assertTrue(root.path("reference_images").isMissingNode()
                || root.path("reference_images").isEmpty());
    }

    @Test
    void wrapsReferenceImagesAsUrlObjects() throws Exception {
        String first = "data:image/png;base64,aaa";
        String second = "data:image/jpeg;base64,bbb";
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "grok-imagine-video",
                "use <IMAGE_0> and <IMAGE_1>",
                "reference2video",
                "16:9",
                "720p",
                5,
                null,
                null,
                List.of(first, second),
                null)));

        assertEquals(2, root.path("reference_images").size());
        assertEquals(first, root.path("reference_images").get(0).path("url").asText());
        assertEquals(second, root.path("reference_images").get(1).path("url").asText());
        assertTrue(root.path("image").isMissingNode() || root.path("image").isNull());
        // 别名 images 不能一起发：New API 的 xAI 插件会把「长度恰为 1 的 images」回填成 image，
        // 单张参考图因此被上游当成首帧。
        assertTrue(root.path("images").isMissingNode() || root.path("images").isEmpty());
    }

    /**
     * 单张参考图是最容易被当成首帧的场景：上游同时收到 image 和 reference_images 时按首帧处理，
     * 成片从这张图开始。这里锁住「参考图模式只有一个入参通道」。
     */
    @Test
    void sendsOnlyReferenceImagesWhenASingleReferenceIsUsed() throws Exception {
        String only = "data:image/png;base64,aaa";
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "grok-imagine-video",
                "use <IMAGE_0> as the character",
                "reference2video",
                "16:9",
                "720p",
                5,
                null,
                null,
                List.of(only),
                null)));

        assertEquals(1, root.path("reference_images").size());
        assertEquals(only, root.path("reference_images").get(0).path("url").asText());
        assertTrue(root.path("image").isMissingNode() || root.path("image").isNull());
        assertTrue(root.path("image_url").isMissingNode() || root.path("image_url").isNull());
        assertTrue(root.path("images").isMissingNode() || root.path("images").isEmpty());
    }

    @Test
    void wrapsLastFrameAsUrlObject() throws Exception {
        String first = "data:image/png;base64,aaa";
        String last = "data:image/png;base64,zzz";
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "grok-imagine-video",
                "from first to last",
                "first-last-frame",
                "16:9",
                "720p",
                5,
                first,
                last,
                List.of(),
                null)));

        assertEquals(first, root.path("image").asText());
        assertEquals(last, root.path("last_frame").path("url").asText());
        assertEquals(last, root.path("last_frame_image").asText());
    }

    /**
     * OpenAI 的 Videos 契约里 {@code seconds} 是字符串，New API 的 task 插件按 Go 的 string 直接
     * 反序列化；发数字会被 sora 插件拒成 400（{@code cannot unmarshal number ... of type string}），
     * 而 xAI 插件两种都收。这里锁住「统一发字符串」，避免以后被改回数字。
     */
    @Test
    void sendsSecondsAsAStringForTaskPluginGateways() throws Exception {
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "seedance-2.0",
                "a cat surfing",
                "text2video",
                "16:9",
                "720p",
                5,
                null,
                null,
                List.of(),
                null)));

        assertTrue(root.path("seconds").isTextual(), "seconds 必须是字符串");
        assertEquals("5", root.path("seconds").asText());
        // duration 与 seconds 同时出现会被上游 422 拒掉。
        assertTrue(root.path("duration").isMissingNode() || root.path("duration").isNull());
    }

    /**
     * seedance 走 doubao 插件 + Ark 形状：有图时必须用 content[] 才能带 role（参考图 vs 首帧）。
     * 插件对带 content 的请求体原样透传，所以这几项要按 Ark 的字面名字发。
     */
    @Test
    void sendsReferenceImagesForBothXaiAndArkInOnePayload() throws Exception {
        String first = "data:image/png;base64,aaa";
        String second = "data:image/jpeg;base64,bbb";
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "seedance2.0 mini Love",
                "use the person in the picture",
                "reference2video",
                "9:16",
                "720p",
                5,
                null,
                null,
                List.of(first, second),
                null)));

        JsonNode content = root.path("content");
        assertEquals(3, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("use the person in the picture", content.get(0).path("text").asText());
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("reference_image", content.get(1).path("role").asText());
        assertEquals(first, content.get(1).path("image_url").path("url").asText());
        assertEquals("reference_image", content.get(2).path("role").asText());
        assertEquals(second, content.get(2).path("image_url").path("url").asText());
        // xAI 同时看到 duration 与 seconds 会 422，所以只发字符串 seconds。
        assertEquals("5", root.path("seconds").asText());
        assertTrue(root.path("duration").isMissingNode() || root.path("duration").isNull());
        assertEquals("720p", root.path("resolution").asText());
        assertEquals("9:16", root.path("ratio").asText());
        // 同一份载荷同时给 xAI 和 Ark 读：xAI 认 reference_images，Ark 认 content 的 role。
        assertEquals(2, root.path("reference_images").size());
        assertEquals("reference_image", content.get(1).path("role").asText());
        // 参考模式下不能出现首帧字段，否则 xAI 会把参考图当开场画面。
        assertTrue(root.path("image").isMissingNode() || root.path("image").isNull());
        // input_reference 语义是「从哪一帧开始」，xAI 插件会把它提升成 image。默认名单为空，
        // 所以参考图绝不能借它上路。
        assertTrue(root.path("input_reference").isMissingNode()
                        || root.path("input_reference").isNull(),
                "参考图模式默认不能带 input_reference，否则会退化成首帧");
        // openai.fit 的简写字段：参考图也可以走 image_urls（数组），与 content 里的 role 并存。
        assertEquals(2, root.path("image_urls").size());
        assertEquals(first, root.path("image_urls").get(0).asText());
    }

    /**
     * 只有名单里声明过的模型才补 input_reference —— 这类模型所在通道的插件只认这个字段，
     * 参考图送不进去就静默降级成文生视频，只能接受「首帧」这层语义。
     */
    @Test
    void addsInputReferenceOnlyForModelsWhoseChannelKeepsNothingElse() throws Exception {
        LlmProperties properties = new LlmProperties();
        properties.setVideoInputReferenceModels(List.of("seedance-2.0", " sora-2 "));
        OpenAiCompatibleVideoGenerationClient listed = new OpenAiCompatibleVideoGenerationClient(
                HttpClient.newHttpClient(), properties, objectMapper);

        String first = "data:image/png;base64,aaa";
        String second = "data:image/jpeg;base64,bbb";
        JsonNode listedRoot = objectMapper.readTree(listed.requestBody(new VideoGenerationCommand(
                "Seedance-2.0",
                "use the person in the picture",
                "reference2video",
                "9:16",
                "720p",
                5,
                null,
                null,
                List.of(first, second),
                null)));
        assertEquals(first, listedRoot.path("input_reference").asText());
        // 参考图本体照旧发，插件丢掉也由它自己决定。
        assertEquals(2, listedRoot.path("reference_images").size());

        JsonNode otherRoot = objectMapper.readTree(listed.requestBody(new VideoGenerationCommand(
                "grok-imagine-video-1.5",
                "use the person in the picture",
                "reference2video",
                "9:16",
                "720p",
                5,
                null,
                null,
                List.of(first, second),
                null)));
        assertTrue(otherRoot.path("input_reference").isMissingNode()
                        || otherRoot.path("input_reference").isNull(),
                "名单外的模型（xAI 通道）不能被补 input_reference");
    }

    @Test
    void sendsFirstFrameForBothXaiAndArkInOnePayload() throws Exception {
        String dataUrl = "data:image/png;base64,aaa";
        JsonNode root = objectMapper.readTree(client.requestBody(new VideoGenerationCommand(
                "seedance-2.0",
                "animate this",
                "image2video",
                "16:9",
                "720p",
                5,
                dataUrl,
                null,
                List.of(),
                null)));

        JsonNode content = root.path("content");
        assertEquals(2, content.size());
        assertEquals("first_frame", content.get(1).path("role").asText());
        assertEquals(dataUrl, content.get(1).path("image_url").path("url").asText());
    }

    /**
     * OpenAI Videos 的查询结果只有状态、没有 URL（{@code {"id":"task_...","status":"completed"}}），
     * 成品放在 {@code /v1/videos/{id}/content}。以前这里直接判失败，已出片、已计费的任务被标成
     * 「succeeded without a result URL」，前端就一直转圈。
     */
    @Test
    void resolvesTheContentRouteWhenTheStatusPayloadCarriesNoUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/videos/task_probe", exchange -> {
            byte[] payload = ("{\"id\":\"task_probe\",\"object\":\"video\",\"model\":\"seedance-2.0\","
                    + "\"status\":\"completed\",\"progress\":100}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            LlmProperties properties = new LlmProperties();
            properties.setBaseUrl(baseUrl);
            properties.setVideosPath("/videos");
            properties.setVideoStatusPath("/v1/videos");
            OpenAiCompatibleVideoGenerationClient polling = new OpenAiCompatibleVideoGenerationClient(
                    HttpClient.newHttpClient(), properties, objectMapper);

            UpstreamVideoStatus status = polling.poll(
                    new ProviderCredentials("OPENAI_COMPATIBLE", baseUrl, "sk-test",
                            null, null, null, "/videos", "/v1/videos", null, null),
                    "task_probe");

            assertEquals(UpstreamVideoStatus.State.SUCCEEDED, status.state());
            assertEquals(baseUrl + "/videos/task_probe/content", status.videoUrl());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 配置了素材上传地址后，data URL 必须先换成公网链接：多数供应商只认 https 图片，
     * 内联 base64 会被静默忽略，任务退化成文生视频。
     */
    @Test
    void uploadsInlineReferenceWhenAssetHostIsConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/uploads", exchange -> {
            byte[] body = "{\"id\":\"f1\",\"url\":\"https://cdn.example/ref.png\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            LlmProperties properties = new LlmProperties();
            properties.setVideoUploadUrl(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/uploads");
            properties.setVideoUploadApiKey("sk-up");
            OpenAiCompatibleVideoGenerationClient uploading =
                    new OpenAiCompatibleVideoGenerationClient(HttpClient.newHttpClient(), properties,
                            objectMapper);

            JsonNode root = objectMapper.readTree(uploading.requestBody(new VideoGenerationCommand(
                    "seedance2.0 mini Love",
                    "use the person in the picture",
                    "reference2video",
                    "9:16",
                    "720p",
                    5,
                    null,
                    null,
                    List.of("data:image/png;base64,YWFh"),
                    null)));

            String uploaded = "https://cdn.example/ref.png";
            assertEquals(uploaded, root.path("image_urls").get(0).asText());
            assertEquals(uploaded, root.path("reference_images").get(0).path("url").asText());
            assertEquals(uploaded, root.path("content").get(1).path("image_url").path("url").asText());
            // 换成公网链接后依然不能落到 input_reference：那会让视频从参考图开始播。
            assertTrue(root.path("input_reference").isMissingNode()
                    || root.path("input_reference").isNull());
        } finally {
            server.stop(0);
        }
    }
}
