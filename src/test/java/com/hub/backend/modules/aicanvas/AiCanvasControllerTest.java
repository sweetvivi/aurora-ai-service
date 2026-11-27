package com.hub.backend.modules.aicanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.ErrorCode;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.infra.llm.ImageEditingClient;
import com.hub.backend.infra.llm.ImageGenerationClient;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageEditingCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.aicanvas.entity.AiCanvasAssetEntity;
import com.hub.backend.modules.aicanvas.entity.AiCanvasTaskEntity;
import com.hub.backend.modules.aicanvas.entity.AiCanvasWorkflowEntity;
import com.hub.backend.modules.aicanvas.AiCanvasVideoUrlDownloader.DownloadedVideo;
import com.hub.backend.modules.aicanvas.repository.AiCanvasAssetRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasChatMessageRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasChatSessionRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasTaskRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasWorkflowRepository;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.repository.AuthSessionRepository;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import com.hub.backend.modules.video.VideoGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiCanvasControllerTest {

    private static final String BASE = "/api/v1/ai-canvas";
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4
    };
    private static final byte[] VIDEO_BYTES = {
            0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'
    };
    private static final byte[] TRIMMED_VIDEO_BYTES = {
            0, 0, 0, 20, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'
    };
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG_BYTES);
    private static final Path STORAGE_ROOT = createTempRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", STORAGE_ROOT::toString);
        registry.add("app.rate-limit.enabled", () -> "false");
        registry.add("app.auth.bcrypt-strength", () -> "4");
        // 断言的是「发给上游的模型名」，固定一个具体模型：部署默认值可能指向回退组 key，
        // 测试库不种目录时会原样透传，混在一起会让断言随部署配置漂移。
        registry.add("app.llm.image-model", () -> "gpt-image-2");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private AiCanvasWorkflowRepository workflowRepository;

    @Autowired
    private AiCanvasAssetRepository assetRepository;

    @Autowired
    private AiCanvasChatSessionRepository sessionRepository;

    @Autowired
    private AiCanvasChatMessageRepository messageRepository;

    @Autowired
    private AiCanvasTaskRepository taskRepository;

    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private ImageGenerationClient imageGenerationClient;

    @MockitoBean
    private ImageEditingClient imageEditingClient;

    @MockitoBean
    private ImageUrlDownloader imageUrlDownloader;

    @MockitoBean
    private AiCanvasFfmpegService ffmpegService;

    @MockitoBean
    private AiCanvasVideoUrlDownloader videoUrlDownloader;

    @MockitoBean
    private AiCanvasPublicUrlGuard publicUrlGuard;

    @MockitoBean
    private UserRelayCredentialResolver relayCredentials;

    /**
     * 画布视频生成是平台视频模块的一层参数翻译：提交、排队、计费都在那边，所以这里替身掉，
     * 只验证画布契约（首尾帧 → 附件、任务号、状态回读）。
     */
    @MockitoBean
    private VideoGenerationService videoGenerationService;

    private Long aliceId;
    private Long bobId;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() throws Exception {
        taskRepository.deleteAll();
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        workflowRepository.deleteAll();
        assetRepository.deleteAll();
        uploadedFileRepository.deleteAll();
        authSessionRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();
        clearStorageRoot();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        var alice = registerCanvasUser("canvas-alice", "Alice");
        var bob = registerCanvasUser("canvas-bob", "Bob");
        aliceId = alice.path("user").path("id").asLong();
        bobId = bob.path("user").path("id").asLong();
        aliceToken = alice.path("accessToken").asText();
        bobToken = bob.path("accessToken").asText();
        // Image paths resolve the image credential; chat/video keep the plain resolver. The two are
        // stubbed with different keys so an image call slipping onto the chat key is visible.
        when(relayCredentials.resolveImage(any())).thenReturn(new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-user-relay",
                null,
                null,
                null));
        when(relayCredentials.resolve(any())).thenReturn(new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-chat-only",
                null,
                null,
                null));
        org.junit.jupiter.api.Assertions.assertTrue(aliceId > 0);
        org.junit.jupiter.api.Assertions.assertTrue(bobId > 0);
        org.junit.jupiter.api.Assertions.assertEquals(site.getId(), alice.path("user").path("siteId").asLong());
    }

    private com.fasterxml.jackson.databind.JsonNode registerCanvasUser(String username, String nickname)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"canvas-password-123","nickname":"%s"}
                                """.formatted(username, nickname)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void workflowCrudPreservesJsonAndIsOwnerScoped() throws Exception {
        String saveBody = mockMvc.perform(post(BASE + "/workflows")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "wf-alice",
                                  "title": "Alice Canvas",
                                  "nodes": [
                                    {"id":"node-1","type":"Image","x":12,"y":34,"parentIds":[]}
                                  ],
                                  "groups": [{"id":"group-1","name":"Scene"}],
                                  "viewport": {"x":40,"y":0,"zoom":0.6}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-alice"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(
                "wf-alice", objectMapper.readTree(saveBody).path("id").asText());

        mockMvc.perform(get(BASE + "/workflows/wf-alice").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].x").value(12))
                .andExpect(jsonPath("$.groups[0].name").value("Scene"))
                .andExpect(jsonPath("$.viewport.zoom").value(0.6))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.updatedAt").value(endsWith("Z")));

        mockMvc.perform(get(BASE + "/workflows").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nodeCount").value(1));

        mockMvc.perform(patch(BASE + "/workflows/wf-alice")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed Canvas\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE + "/workflows/wf-alice").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/workflows/wf-alice").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(BASE + "/workflows/wf-alice").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed Canvas"));

        workflowRepository.save(new AiCanvasWorkflowEntity(
                "cat-demo", null, "Public Demo", "demo", "[]", "[]",
                "{\"x\":0,\"y\":0,\"zoom\":1}", null, null,
                AiCanvasWorkflowEntity.STATUS_PUBLIC));
        mockMvc.perform(get(BASE + "/public-workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("cat-demo"));
        mockMvc.perform(get(BASE + "/public-workflows/cat-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Public Demo"));

        mockMvc.perform(delete(BASE + "/workflows/wf-alice").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void assetUploadSupportsRangeAndRejectsForeignAccess() throws Exception {
        String body = mockMvc.perform(multipart(BASE + "/assets")
                        .file(assetPng())
                        .param("category", "scene")
                        .param("name", "角色场景.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(startsWith("asset-")))
                .andExpect(jsonPath("$.type").value("image"))
                .andExpect(jsonPath("$.category").value("scene"))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(body).path("key").asText();

        byte[] firstFour = mockMvc.perform(get(BASE + "/assets/" + key + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 0-3/" + PNG_BYTES.length))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                Arrays.copyOf(PNG_BYTES, 4),
                firstFour);

        mockMvc.perform(get(BASE + "/assets").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(BASE + "/assets/" + key + "/content").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/assets/" + key).header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/assets/" + key).header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void assetLinkOnlyReferencesTheAttachmentAndIsIdempotent() throws Exception {
        // 生成页把出图同步进素材库时不该再复制一份字节：同一张图只应占附件那一份空间。
        long fileId = uploadAttachment(assetPng(), aliceToken);

        String first = mockMvc.perform(post(BASE + "/assets/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"category":"other","name":"一只猫-20261103-093000",
                                 "prompt":"一只猫"}""".formatted(fileId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceFileId").value((int) fileId))
                .andExpect(jsonPath("$.type").value("image"))
                // 调用方给的名字没有扩展名，后端按附件类型补上
                .andExpect(jsonPath("$.name").value("一只猫-20261103-093000.png"))
                .andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(first).path("key").asText();

        // 读素材内容就是读附件内容：字节没有第二份
        byte[] bytes = mockMvc.perform(get(BASE + "/assets/" + key + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(PNG_BYTES, bytes);

        // 同一附件再同步一次返回同一条素材，不会翻倍
        String again = mockMvc.perform(post(BASE + "/assets/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":%d,\"category\":\"other\"}".formatted(fileId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(key, objectMapper.readTree(again).path("key").asText());

        AiCanvasAssetEntity asset = assetRepository.findById(key).orElseThrow();
        assertEquals(fileId, asset.getSourceFileId());
        org.junit.jupiter.api.Assertions.assertNull(asset.getStorageKey());
        // 引用行不进画布配额：字节已经算在附件配额里，重复计一次等于让用户付两次空间
        assertEquals(0, assetRepository.sumOwnedSizeByUserIdAndStatus(
                asset.getUserId(), AiCanvasAssetEntity.STATUS_ACTIVE));
    }

    @Test
    void assetLinkRefusesForeignAttachmentsAndExplainsADeletedSource() throws Exception {
        long fileId = uploadAttachment(assetPng(), aliceToken);

        // 别人的附件不能加进自己的素材库，且不泄露存在性
        mockMvc.perform(post(BASE + "/assets/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":%d,\"category\":\"other\"}".formatted(fileId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        String body = mockMvc.perform(post(BASE + "/assets/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":%d,\"category\":\"other\"}".formatted(fileId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(body).path("key").asText();

        // V88 起「在作品页删除」= 全部删除：指向它的素材行跟着消失，而不是留一张打不开的卡片
        mockMvc.perform(delete("/api/v1/files/" + fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE + "/assets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(key))));

        mockMvc.perform(get(BASE + "/assets/" + key + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingALinkedAssetKeepsTheAttachment() throws Exception {
        // 素材只是引用，删素材不能把作品页那张图一起带走
        long fileId = uploadAttachment(assetPng(), aliceToken);

        String body = mockMvc.perform(post(BASE + "/assets/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":%d,\"category\":\"other\"}".formatted(fileId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String key = objectMapper.readTree(body).path("key").asText();

        mockMvc.perform(delete(BASE + "/assets/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        byte[] bytes = mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(PNG_BYTES, bytes);
    }

    private long uploadAttachment(MockMultipartFile file, String token) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("id").asLong();
    }

    @Test
    void assetUploadAcceptsFormatAliasesAndDerivesMissingTypes() throws Exception {
        // image/jpg 是部分导出工具上报的别名，旧实现直接 400。
        String jpgBody = mockMvc.perform(multipart(BASE + "/assets")
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpg", PNG_BYTES))
                        .param("category", "other")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mimeType").value("image/jpeg"))
                .andReturn().getResponse().getContentAsString();
        String jpgKey = objectMapper.readTree(jpgBody).path("key").asText();
        mockMvc.perform(get(BASE + "/assets/" + jpgKey + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // 没有扩展名的文件按 MIME 归一化落库。
        String bareBody = mockMvc.perform(multipart(BASE + "/assets")
                        .file(new MockMultipartFile("file", "download", "image/png", PNG_BYTES))
                        .param("category", "other")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(objectMapper.readTree(bareBody).path("key").asText().startsWith("asset-"));

        // SVG 落库后禁止脚本执行。
        String svgBody = mockMvc.perform(multipart(BASE + "/assets")
                        .file(new MockMultipartFile("file", "logo.svg", "image/svg+xml",
                                "<svg/>".getBytes(StandardCharsets.UTF_8)))
                        .param("category", "other")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mimeType").value("image/svg+xml"))
                .andReturn().getResponse().getContentAsString();
        String svgKey = objectMapper.readTree(svgBody).path("key").asText();
        mockMvc.perform(get(BASE + "/assets/" + svgKey + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; sandbox"));

        // 仍不支持的格式返回 400 与可读原因。
        mockMvc.perform(multipart(BASE + "/assets")
                        .file(new MockMultipartFile("file", "photo.heic", "image/heic", PNG_BYTES))
                        .param("category", "other")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("不支持的文件格式，请上传常见图片或视频"));
    }

    @Test
    void imageGenerationSupportsTextAndMultipleReferenceImages() throws Exception {
        when(imageGenerationClient.generate(any())).thenReturn(new ImageGenerationResult(
                1700000000L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, "一只橘猫"))));
        when(imageEditingClient.edit(any())).thenReturn(new ImageGenerationResult(
                1700000001L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, "修改后的橘猫"))));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .param("prompt", "一只橘猫")
                        .param("aspectRatio", "1:1")
                        .param("resolution", "1K")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(startsWith("/api/v1/files/")))
                .andExpect(jsonPath("$.aspectRatio").value("1/1"))
                .andExpect(jsonPath("$.async").value(false));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .file(png())
                        .param("prompt", "把背景改成夜晚")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url")
                        .value(startsWith("/api/v1/files/")));

        MockMultipartFile second = new MockMultipartFile(
                "images", "second.png", "image/png", PNG_BYTES);
        mockMvc.perform(multipart(BASE + "/images/generations")
                        .file(png())
                        .file(second)
                        .param("prompt", "multi reference")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url")
                        .value(startsWith("/api/v1/files/")));

        ArgumentCaptor<ImageEditingCommand> commands =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, atLeastOnce()).edit(commands.capture());
        assertEquals(2, commands.getValue().images().size());
    }

    @Test
    void imageEditingRetriesAnUnpricedModelWithTheConfiguredDefault() throws Exception {
        when(imageEditingClient.edit(any()))
                .thenThrow(new LlmInvocationException(
                        "Image editing gateway returned HTTP 400: "
                                + "{\"code\":\"model_price_error\","
                                + "\"message\":\"Model gpt-image-2.5 has not been priced\"}"))
                .thenReturn(new ImageGenerationResult(
                        1700000001L,
                        "gpt-image-2",
                        List.of(new GeneratedImage(PNG_BASE64, null, "修改后的橘猫"))));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .file(png())
                        .param("prompt", "把背景改成夜晚")
                        .param("imageModel", "gpt-image-2.5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(startsWith("/api/v1/files/")));

        ArgumentCaptor<ImageEditingCommand> commands =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, org.mockito.Mockito.times(2)).edit(commands.capture());
        assertEquals("gpt-image-2.5", commands.getAllValues().get(0).model());
        assertEquals("gpt-image-2", commands.getAllValues().get(1).model());
    }

    @Test
    void rejectsImageGenerationBeforeReadingReferencesOrCallingModelsWhenBalanceIsZero()
            throws Exception {
        doThrow(new PaymentRequiredException("Insufficient account balance"))
                .when(relayCredentials).resolveImage(any());

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .file(new MockMultipartFile(
                                "image", "reference.png", "image/png", PNG_BYTES))
                        .param("prompt", "a blue circle")
                        .param("imageBase64", "not-valid-base64")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        assertEquals(0, uploadedFileRepository.count());
        assertEquals(0, assetRepository.count());
        verifyNoInteractions(
                imageGenerationClient, imageEditingClient, imageUrlDownloader);
    }

    @Test
    void chatStoresMessagesAndKeepsSessionsPrivate() throws Exception {
        when(llmClient.complete(any())).thenReturn(completion("可以，把镜头改成俯视。"));

        String created = mockMvc.perform(post(BASE + "/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新对话"))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andExpect(jsonPath("$.createdAt").value(endsWith("Z")))
                .andExpect(jsonPath("$.updatedAt").value(endsWith("Z")))
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(created).path("id").asText();

        mockMvc.perform(post(BASE + "/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId":"%s",
                                  "message":"这个镜头改成俯视",
                                  "media":[]
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("可以，把镜头改成俯视。"))
                .andExpect(jsonPath("$.topic").value("这个镜头改成俯视"));

        mockMvc.perform(get(BASE + "/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].messageCount").value(2));

        mockMvc.perform(get(BASE + "/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"));

        mockMvc.perform(get(BASE + "/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(BASE + "/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void promptAndStoryboardEndpointsReturnDocumentedShapes() throws Exception {
        String assetBody = mockMvc.perform(multipart(BASE + "/assets")
                        .file(assetPng())
                        .param("category", "character")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String assetUrl = objectMapper.readTree(assetBody).path("url").asText();

        when(llmClient.complete(any())).thenReturn(completion("一只橘猫坐在窗边，电影感逆光。"));
        mockMvc.perform(post(BASE + "/prompt/optimize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"一只猫\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimizedPrompt")
                        .value("一只橘猫坐在窗边，电影感逆光。"));

        mockMvc.perform(post(BASE + "/prompt/describe-image")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageUrl":"%s","prompt":"描述画面"}
                                """.formatted(assetUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description")
                        .value("一只橘猫坐在窗边，电影感逆光。"));

        String scriptsJson = """
                {
                  "scripts":[
                    {
                      "sceneNumber":1,
                      "description":"开场全景",
                      "cameraAngle":"俯视 45 度",
                      "cameraMovement":"缓慢推近",
                      "lighting":"黄昏侧逆光",
                      "mood":"温暖",
                      "prompt":"橘猫坐在窗边"
                    },
                    {
                      "sceneNumber":2,
                      "description":"橘猫转头",
                      "cameraAngle":"近景",
                      "cameraMovement":"手持跟拍",
                      "lighting":"室内暖光",
                      "mood":"好奇",
                      "prompt":"橘猫看向镜头"
                    }
                  ],
                  "styleAnchor":"暖色写实",
                  "characterDNA":{"cat":"orange"}
                }
                """;
        when(llmClient.complete(any())).thenReturn(completion(scriptsJson));
        mockMvc.perform(post(BASE + "/storyboard/generate-scripts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "story":"一只橘猫等待主人回家。",
                                  "characterDescriptions":[
                                    {"name":"橘猫","description":"圆脸，绿色眼睛"}
                                  ],
                                  "sceneCount":2,
                                  "referenceImages":[]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripts", hasSize(2)))
                .andExpect(jsonPath("$.scripts[0].cameraAngle").value("俯视 45 度"))
                .andExpect(jsonPath("$.scripts[0].cameraMovement").value("缓慢推近"))
                .andExpect(jsonPath("$.scripts[0].lighting").value("黄昏侧逆光"))
                .andExpect(jsonPath("$.scripts[1].cameraMovement").value("手持跟拍"))
                .andExpect(jsonPath("$.scripts[1].lighting").value("室内暖光"))
                .andExpect(jsonPath("$.styleAnchor").value("暖色写实"))
                .andExpect(jsonPath("$.characterDNA.cat").value("orange"));

        when(imageGenerationClient.generate(any())).thenReturn(new ImageGenerationResult(
                1700000002L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, null))));
        mockMvc.perform(post(BASE + "/storyboard/generate-composite")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scripts":[{"sceneNumber":1,"prompt":"橘猫坐在窗边"}],
                                  "styleAnchor":"暖色写实",
                                  "characterDNA":{"cat":"orange"},
                                  "sceneCount":1,
                                  "referenceImages":[]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(startsWith("/api/v1/files/")));
    }

    @Test
    void videoGenerationTranslatesFramesIntoAnUploadedReferenceAndMirrorsTheTask() throws Exception {
        when(videoGenerationService.submit(any(), any())).thenReturn(platformVideoTask(
                "vt_abc", "queued", 0, null, null));

        mockMvc.perform(post(BASE + "/videos/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt":"橘猫慢慢转头",
                                  "videoModel":"doubao-seedance-2-0",
                                  "aspectRatio":"Auto",
                                  "resolution":"720p",
                                  "duration":5,
                                  "imageBase64":"data:image/png;base64,%s"
                                }
                                """.formatted(PNG_BASE64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.async").value(true))
                .andExpect(jsonPath("$.taskId").value("vt_abc"))
                .andExpect(jsonPath("$.status").value("running"));

        ArgumentCaptor<com.hub.backend.modules.video.dto.VideoGenerationRequest> submitted =
                ArgumentCaptor.forClass(com.hub.backend.modules.video.dto.VideoGenerationRequest.class);
        verify(videoGenerationService).submit(any(), submitted.capture());
        com.hub.backend.modules.video.dto.VideoGenerationRequest request = submitted.getValue();
        org.junit.jupiter.api.Assertions.assertNotNull(request.referenceFileId());
        org.junit.jupiter.api.Assertions.assertNull(request.lastFrameFileId());
        org.junit.jupiter.api.Assertions.assertEquals("image2video", request.mode());
        // 「Auto」在平台视频模块里等于不传，原样透传会让能力校验把请求判成不支持。
        org.junit.jupiter.api.Assertions.assertNull(request.aspectRatio());
        org.junit.jupiter.api.Assertions.assertEquals("720p", request.resolution());
        org.junit.jupiter.api.Assertions.assertEquals(1, uploadedFileRepository.count());

        AiCanvasTaskEntity mirrored = taskRepository.findById("vt_abc").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(aliceId, mirrored.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(AiCanvasTaskEntity.TYPE_VIDEO, mirrored.getTaskType());
        org.junit.jupiter.api.Assertions.assertEquals(AiCanvasTaskEntity.STATUS_RUNNING, mirrored.getStatus());
    }

    @Test
    void videoTaskStatusFollowsTheVideoModuleAndSyncsTheHistoryRow() throws Exception {
        taskRepository.save(new AiCanvasTaskEntity(
                "vt_abc", aliceId, AiCanvasTaskEntity.TYPE_VIDEO, null,
                "doubao-seedance-2-0", "doubao-seedance-2-0", "橘猫转头",
                AiCanvasTaskEntity.STATUS_RUNNING, 10, null, null));
        when(videoGenerationService.get("vt_abc", aliceId)).thenReturn(platformVideoTask(
                "vt_abc", "succeeded", 100, "/api/v1/files/9/content", null));

        mockMvc.perform(get(BASE + "/tasks/vt_abc").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.videoUrl").value("/api/v1/files/9/content"));
        mockMvc.perform(get(BASE + "/tasks/vt_abc").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        AiCanvasTaskEntity synced = taskRepository.findById("vt_abc").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(AiCanvasTaskEntity.STATUS_SUCCEEDED, synced.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(100, synced.getProgress());

        mockMvc.perform(get(BASE + "/video-tasks").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].status").value("succeeded"))
                .andExpect(jsonPath("$.tasks[0].videoUrl").value("/api/v1/files/9/content"))
                .andExpect(jsonPath("$.tasks[0].model").value("doubao-seedance-2-0"));
        mockMvc.perform(get(BASE + "/video-tasks").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", hasSize(0)));
    }

    @Test
    void videoTaskKeepsReadingItsRowWhenTheVideoModuleNoLongerKnowsIt() throws Exception {
        taskRepository.save(new AiCanvasTaskEntity(
                "video-task-1", aliceId, AiCanvasTaskEntity.TYPE_VIDEO, null,
                "doubao-seedance-2-0", "doubao-seedance-2-0", "橘猫转头",
                AiCanvasTaskEntity.STATUS_SUCCEEDED, 100,
                "{\"videoUrl\":\"/api/v1/files/1/content\"}", null));
        when(videoGenerationService.get("video-task-1", aliceId)).thenThrow(
                new com.hub.backend.common.exception.NotFoundException("视频任务 video-task-1 不存在"));

        mockMvc.perform(get(BASE + "/tasks/video-task-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.videoUrl").value("/api/v1/files/1/content"));
    }

    @Test
    void rejectedVideoGenerationStillCleansUpTheStoredReferenceFrame() throws Exception {
        when(videoGenerationService.submit(any(), any()))
                .thenThrow(new PaymentRequiredException("Insufficient account balance"));

        mockMvc.perform(post(BASE + "/videos/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt":"橘猫慢慢转头",
                                  "videoModel":"doubao-seedance-2-0",
                                  "duration":5,
                                  "imageBase64":"%s"
                                }
                                """.formatted(PNG_BASE64)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        // 提交没成功，落盘的首帧必须回滚，否则每次余额不足都会在用户存储里留一张孤儿图。
        // 附件是软删除（行保留、blob 释放），所以断言的是状态而不是行数。
        var remaining = uploadedFileRepository.findAll().stream()
                .filter(file -> com.hub.backend.modules.file.entity.UploadedFileEntity.STATUS_UPLOADED
                        .equals(file.getStatus()))
                .toList();
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining.size());
    }

    @Test
    void videoGenerationRejectsMotionReferenceInsteadOfIgnoringIt() throws Exception {
        mockMvc.perform(post(BASE + "/videos/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prompt":"橘猫慢慢转头",
                                  "videoModel":"kling-v2-6",
                                  "motionReferenceUrl":"/api/v1/files/3/content"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // 只断言「没有走到提交」，不要用 verifyNoInteractions：
        // VideoTaskPoller 是 @Scheduled 的，同一上下文里的后台线程随时会调 poll，
        // 那种调用与本次请求无关，却会让整个 mock 的 «零交互» 断言随机失败。
        verify(videoGenerationService, never()).submit(any(), any());
    }

    /** 平台视频模块的任务视图，测试只关心画布用到的几个字段。 */
    private static com.hub.backend.modules.video.dto.VideoTaskResponse platformVideoTask(
            String taskId, String status, int progress, String videoUrl, String errorMessage) {
        return new com.hub.backend.modules.video.dto.VideoTaskResponse(
                taskId, status, progress, "doubao-seedance-2-0", "橘猫转头", "image2video",
                "16:9", "720p", 5, null, null, null, null, java.util.List.of(), java.util.List.of(),
                videoUrl, videoUrl == null ? null : 9L, errorMessage, null, null);
    }

    @Test
    void videoTrimAcceptsBase64AndPublicUrl() throws Exception {
        when(ffmpegService.trim(any(byte[].class), anyString(), anyDouble(), anyDouble()))
                .thenReturn(TRIMMED_VIDEO_BYTES);

        String sourceBase64 = Base64.getEncoder().encodeToString(VIDEO_BYTES);
        mockMvc.perform(post(BASE + "/videos/trim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoDataUrl":"data:video/mp4;base64,%s",
                                  "startTime":1,
                                  "endTime":2.5
                                }
                                """.formatted(sourceBase64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl")
                        .value(startsWith("/api/v1/ai-canvas/assets/")));
        verify(ffmpegService).trim(
                eq(VIDEO_BYTES), eq("mp4"), eq(1.0), eq(2.5));

        when(videoUrlDownloader.download("https://cdn.example.com/input.webm"))
                .thenReturn(new DownloadedVideo(VIDEO_BYTES, "webm", "video/webm"));
        mockMvc.perform(post(BASE + "/videos/trim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoDataUrl":"https://cdn.example.com/input.webm",
                                  "startTime":0.5,
                                  "endTime":1.5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl")
                        .value(startsWith("/api/v1/ai-canvas/assets/")));
        verify(ffmpegService).trim(
                eq(VIDEO_BYTES), eq("webm"), eq(0.5), eq(1.5));

        when(videoUrlDownloader.download("http://127.0.0.1/private.mp4"))
                .thenThrow(new InvalidRequestException("不允许访问内网或保留地址"));
        mockMvc.perform(post(BASE + "/videos/trim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoDataUrl":"http://127.0.0.1/private.mp4",
                                  "startTime":0,
                                  "endTime":1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT))
                .andExpect(jsonPath("$.msg").value("不允许访问内网或保留地址"));
    }

    @Test
    void publishesAllCanvasPathsInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/workflows'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/workflows'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/workflows/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/workflows/{id}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/workflows/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/workflows/{id}/cover'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/public-workflows'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/public-workflows/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/assets'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/assets'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/assets/{key}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/assets/{key}/content'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/images/generations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/videos/generations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/videos/trim'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/tasks/{taskId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/video-tasks'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/prompt/optimize'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/prompt/describe-image'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/chat/sessions'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/chat/sessions'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/chat/sessions/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/chat/sessions/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/chat'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/storyboard/brainstorm-story'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/storyboard/optimize-story'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/storyboard/generate-scripts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/storyboard/generate-composite'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/local-models'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-canvas/local-models/generate'].post").exists());
    }

    @Test
    void privateEndpointsRequireBearerAndIgnoreDevelopmentUserHeader() throws Exception {
        String anonymousBody = mockMvc.perform(get(BASE + "/workflows"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTH_EXPIRED))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.msg").value("请先登录后再试"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(anonymousBody.contains("请先登录"));

        for (String path : List.of(
                "/workflows",
                "/assets",
                "/chat/sessions",
                "/video-tasks",
                "/local-models")) {
            mockMvc.perform(get(BASE + path)
                            .header("X-User-Id", aliceId.toString()))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post(BASE + "/workflows")
                        .header("X-User-Id", aliceId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"anonymous write",
                                  "nodes":[],
                                  "groups":[],
                                  "viewport":{"x":0,"y":0,"zoom":1}
                                }
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .header("X-User-Id", aliceId.toString())
                        .param("prompt", "anonymous generation"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(BASE + "/public-workflows"))
                .andExpect(status().isOk());
    }

    @Test
    void imageGenerationAcceptsDataUriBase64Reference() throws Exception {
        when(imageEditingClient.edit(any())).thenReturn(new ImageGenerationResult(
                1700000003L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, "data URI reference"))));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .param("prompt", "change the background")
                        .param("imageBase64", "data:image/png;base64," + PNG_BASE64)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(startsWith("/api/v1/files/")));

        ArgumentCaptor<ImageEditingCommand> command =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient).edit(command.capture());
        assertEquals("image/png", command.getValue().images().get(0).mimeType());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                PNG_BYTES, command.getValue().images().get(0).content());
    }

    @Test
    void imageGenerationAcceptsPlatformReferenceUrlWithCacheBuster() throws Exception {
        // 平台生成的图片地址是 /api/v1/files/{id}/content；画布会追加 `?t=` 缓存戳后再回传。
        // 后端必须仍把它识别为平台地址走附件读取，而不是当成外部直链去下载（那样会 502）。
        when(imageGenerationClient.generate(any())).thenReturn(new ImageGenerationResult(
                1700000010L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, "first pass"))));
        String generatedUrl = objectMapper.readTree(mockMvc.perform(
                        multipart(BASE + "/images/generations")
                                .param("prompt", "一只橘猫")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("url").asText();
        assertTrue(generatedUrl.startsWith("/api/v1/files/"), generatedUrl);

        when(imageEditingClient.edit(any())).thenReturn(new ImageGenerationResult(
                1700000011L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, "人物更小一点"))));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .param("prompt", "人物更小一点")
                        .param("referenceUrls", generatedUrl + "?t=1699999999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(startsWith("/api/v1/files/")));

        ArgumentCaptor<ImageEditingCommand> command =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient).edit(command.capture());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                PNG_BYTES, command.getValue().images().get(0).content());
        verify(imageUrlDownloader, never()).download(anyString());
    }

    @Test
    void imageGenerationDownloadsReferenceUrlsServerSide() throws Exception {
        String imageUrl = "https://canvas.ai1foo.com/workflows/assets/cat.jpg";
        when(imageUrlDownloader.download(imageUrl))
                .thenReturn(new DownloadedImage(PNG_BYTES, "image/jpeg"));
        when(imageEditingClient.edit(any())).thenReturn(new ImageGenerationResult(
                1700000004L, "gpt-image-2",
                List.of(new GeneratedImage(PNG_BASE64, null, "URL reference"))));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .param("prompt", "给这只猫戴上帽子")
                        .param("referenceUrls", imageUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(startsWith("/api/v1/files/")));

        verify(imageUrlDownloader).download(imageUrl);
        ArgumentCaptor<ImageEditingCommand> command =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient).edit(command.capture());
        assertEquals("image/jpeg", command.getValue().images().get(0).mimeType());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                PNG_BYTES, command.getValue().images().get(0).content());
    }

    @Test
    void imageProxyStreamsCrossOriginImagesForAuthenticatedUsers() throws Exception {
        String imageUrl = "https://canvas.ai1foo.com/workflows/assets/cat.jpg";
        when(publicUrlGuard.validate(eq(imageUrl), anyString())).thenReturn(URI.create(imageUrl));
        when(imageUrlDownloader.download(imageUrl))
                .thenReturn(new DownloadedImage(PNG_BYTES, "image/jpeg"));

        byte[] bytes = mockMvc.perform(get(BASE + "/image-proxy")
                        .param("url", imageUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"))
                .andReturn().getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(PNG_BYTES, bytes);
        verify(imageUrlDownloader).download(imageUrl);

        mockMvc.perform(get(BASE + "/image-proxy").param("url", imageUrl))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void imageProxyRejectsBlockedTargetsAndNonRasterContent() throws Exception {
        String blocked = "http://127.0.0.1/secret.png";
        when(publicUrlGuard.validate(eq(blocked), anyString()))
                .thenThrow(new InvalidRequestException("不允许访问内网或保留地址"));

        mockMvc.perform(get(BASE + "/image-proxy")
                        .param("url", blocked)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT))
                .andExpect(jsonPath("$.msg").value("不允许访问内网或保留地址"));

        String vector = "https://cdn.example.com/logo.svg";
        when(publicUrlGuard.validate(eq(vector), anyString())).thenReturn(URI.create(vector));
        when(imageUrlDownloader.download(vector))
                .thenReturn(new DownloadedImage("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml"));

        mockMvc.perform(get(BASE + "/image-proxy")
                        .param("url", vector)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("该接口只支持位图（PNG / JPEG / WebP）"));
    }

    @Test
    void multipartEndpointsAreDocumentedAsFormBodies() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode docs = objectMapper.readTree(body);

        com.fasterxml.jackson.databind.JsonNode imageOperation = docs.at(
                "/paths/~1api~1v1~1ai-canvas~1images~1generations/post");
        com.fasterxml.jackson.databind.JsonNode imageForm = multipartSchema(docs, imageOperation);
        assertTrue(imageForm.path("properties").has("prompt"));
        assertTrue(imageForm.path("properties").has("images"));
        assertTrue(imageForm.path("properties").has("image"));
        assertTrue(imageForm.path("properties").has("imageBase64"));
        assertTrue(imageForm.path("properties").has("referenceUrls"));
        assertFalse(hasParameter(imageOperation.path("parameters"), "prompt"));

        com.fasterxml.jackson.databind.JsonNode assetOperation = docs.at(
                "/paths/~1api~1v1~1ai-canvas~1assets/post");
        com.fasterxml.jackson.databind.JsonNode assetForm = multipartSchema(docs, assetOperation);
        assertTrue(assetForm.path("properties").has("file"));
        assertTrue(assetForm.path("properties").has("category"));
        assertFalse(hasParameter(assetOperation.path("parameters"), "category"));
    }

    @Test
    void protocolErrorsUseTheCompatibleErrorEnvelope() throws Exception {
        mockMvc.perform(post(BASE + "/images/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT))
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.msg").value("请求参数有误"));

        mockMvc.perform(multipart(BASE + "/images/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.msg").value("请求参数有误"))
                .andExpect(jsonPath("$.details.prompt").exists());

        mockMvc.perform(get(BASE + "/missing-resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.msg").value("内容不存在或已被删除"));
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("images", "reference.png", "image/png", PNG_BYTES);
    }

    private MockMultipartFile assetPng() {
        return new MockMultipartFile("file", "asset.png", "image/png", PNG_BYTES);
    }

    private static LlmChatCompletion completion(String content) {
        return new LlmChatCompletion(
                "cmpl-canvas", "deepseek-v4.1-flash", content, "stop",
                new LlmUsage(10, 4, 14));
    }

    private static com.fasterxml.jackson.databind.JsonNode multipartSchema(
            com.fasterxml.jackson.databind.JsonNode docs,
            com.fasterxml.jackson.databind.JsonNode operation) {
        com.fasterxml.jackson.databind.JsonNode schema = operation
                .path("requestBody")
                .path("content")
                .path("multipart/form-data")
                .path("schema");
        String reference = schema.path("$ref").asText();
        return reference.startsWith("#/") ? docs.at(reference.substring(1)) : schema;
    }

    private static boolean hasParameter(com.fasterxml.jackson.databind.JsonNode parameters,
                                        String name) {
        if (!parameters.isArray()) {
            return false;
        }
        for (com.fasterxml.jackson.databind.JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("backend-ai-canvas-test");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void clearStorageRoot() throws IOException {
        if (!Files.isDirectory(STORAGE_ROOT)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(STORAGE_ROOT)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }
}
