package com.hub.backend.modules.smartedit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.infra.llm.ImageEditingClient;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageEditingCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.image.ImageGenerationService;
import com.hub.backend.modules.image.dto.GeneratedImageResponse;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the smart-editing image tools.
 *
 * <p>The upstream image services are replaced because the tests are about request/response shape,
 * file validation and provider routing. Real uploads still go through the local storage adapter so
 * the returned platform URLs are the same ones the browser receives.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmartEditImageControllerTest extends AuthenticatedControllerTestSupport {

    private static final String PREFIX = "/api/v1/smart-editing";
    private static final byte[] PNG_BYTES = createPng(false);
    private static final byte[] MASK_BYTES = createPng(true);
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG_BYTES);

    /** Created at class load so the Spring context can read it before the first request. */
    private static final Path UPLOAD_ROOT = createTempRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", UPLOAD_ROOT::toString);
        // 这里断言的是「发给上游的模型名」，所以固定一个具体模型。
        // 部署默认值可能指向回退组 key（目录里才有组行），测试库不种目录时会原样透传，
        // 两者混在一起会让这条断言随部署配置漂移。
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
    private LlmModelRepository modelRepository;

    @Autowired
    private LlmModelFallbackGroupRepository fallbackGroupRepository;

    @Autowired
    private LlmModelFallbackMemberRepository fallbackMemberRepository;

    @Autowired
    private LlmProviderRepository providerRepository;

    @MockitoBean
    private ImageEditingClient imageEditingClient;

    @MockitoBean
    private ImageGenerationService imageGenerationService;

    @MockitoBean
    private ImageUrlDownloader imageUrlDownloader;

    @MockitoBean
    private UserRelayCredentialResolver relayCredentials;

    @MockitoSpyBean
    private MaskedImageCompositor maskedImageCompositor;

    private Long siteId;
    private Long userId;
    private String userToken;

    @BeforeEach
    void seedAccount() {
        uploadedFileRepository.deleteAll();
        fallbackMemberRepository.deleteAll();
        fallbackGroupRepository.deleteAll();
        modelRepository.deleteAll();
        providerRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        siteId = site.getId();
        userId = userAccountRepository
                .save(new UserAccountEntity(siteId, "smart-editor", "Smart Editor")).getId();
        userToken = accessToken(userId);
        // Image endpoints must use resolveImage(). resolve() is stubbed with a deliberately
        // different key so a regression that routes an image call through the chat credential
        // shows up as a changed apiKey instead of passing silently.
        when(relayCredentials.resolveImage(any())).thenReturn(imageCredentials());
        when(relayCredentials.resolve(any())).thenReturn(chatCredentials());
        // 模型解析出来之后走的是带 modelId 的重载（按分组取钥匙）
        when(relayCredentials.resolveImage(any(), any())).thenReturn(imageCredentials());
        when(relayCredentials.resolve(any(), any())).thenReturn(chatCredentials());
    }

    private static ProviderCredentials imageCredentials() {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-user-relay",
                null,
                null,
                null);
    }

    private static ProviderCredentials chatCredentials() {
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-chat-only",
                null,
                null,
                null);
    }

    // ---------------------------------------------------------------- models

    @Test
    void returnsOnlyActiveImageModelsWithThePublicKeyAndUpstreamRoute() throws Exception {
        LlmModelEntity image = new LlmModelEntity(
                "doubao-seedream-4-0-250828",
                "Seedream-4.0",
                "字节跳动",
                "huoshan/doubao-seedream-4-0-250828");
        image.setSiteId(siteId);
        image.setModelType(LlmModelEntity.TYPE_IMAGE);
        image.setConsumePoints(new java.math.BigDecimal("3.000000"));
        image = modelRepository.save(image);
        LlmModelFallbackGroupEntity imageVersion = fallbackGroupRepository.save(
                new LlmModelFallbackGroupEntity(
                        siteId, "seedream-4.0", "Seedream 4.0", LlmModelEntity.TYPE_IMAGE));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(imageVersion.getId(), image.getId(), 0));

        LlmModelEntity chat = new LlmModelEntity(
                "deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek", null);
        chat.setSiteId(siteId);
        chat.setModelType(LlmModelEntity.TYPE_CHAT);
        modelRepository.save(chat);

        LlmModelEntity retired = new LlmModelEntity(
                "retired-image", "Retired image", "Vendor", "vendor/retired-image");
        retired.setSiteId(siteId);
        retired.setModelType(LlmModelEntity.TYPE_IMAGE);
        retired.setStatus("DISABLED");
        modelRepository.save(retired);

        mockMvc.perform(get(PREFIX + "/models")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].key").value("seedream-4.0"))
                .andExpect(jsonPath("$[0].name").value("Seedream 4.0"))
                .andExpect(jsonPath("$[0].model").value("seedream-4.0"))
                .andExpect(jsonPath("$[0].company").value("字节跳动"))
                .andExpect(jsonPath("$[0].consume_points").value(3.0))
                .andExpect(jsonPath("$[0].draw.custom_size_min").value(512))
                .andExpect(jsonPath("$[0].draw.custom_size_max").value(4096))
                .andExpect(jsonPath("$[0].draw.image_max").value(4))
                .andExpect(jsonPath("$[0].draw.max_number").value(1))
                .andExpect(jsonPath("$[0].other.photoshop_model").value(true))
                .andExpect(jsonPath("$[0].route").doesNotExist());
    }

    // ---------------------------------------------------------------- upload

    @Test
    void uploadsAValidImageAndReturnsThePlatformAsset() throws Exception {
        mockMvc.perform(multipart(PREFIX + "/files")
                        .file(png("file"))
                        .param("save", "canvas")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("/api/v1/files/")))
                .andExpect(jsonPath("$.object").isString())
                .andExpect(jsonPath("$.assetType").value("reference"));

        assertEquals(1, uploadedFileRepository.count());
    }

    @Test
    void rejectsEmptyUnsupportedOversizedAndInvalidSaveUploads() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]);
        mockMvc.perform(multipart(PREFIX + "/files")
                        .file(empty)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        MockMultipartFile unsupported = new MockMultipartFile(
                "file", "payload.txt", "text/plain", PNG_BYTES);
        mockMvc.perform(multipart(PREFIX + "/files")
                        .file(unsupported)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        MockMultipartFile oversized = new MockMultipartFile(
                "file", "large.png", "image/png", new byte[10 * 1024 * 1024 + 1]);
        mockMvc.perform(multipart(PREFIX + "/files")
                        .file(oversized)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));

        mockMvc.perform(multipart(PREFIX + "/files")
                        .file(png("file"))
                        .param("save", "thumbnail")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("只能是 canvas 或 crop")));

        assertEquals(0, uploadedFileRepository.count());
    }

    // ---------------------------------------------------------------- one-image tools

    @Test
    void routesMattingVariantsAndEnhanceThroughTheEditingClient() throws Exception {
        stubEditedPng();
        String imageUrl = uploadPng("source.png", "file");

        assertSingleImageEdit("/matting", imageUrl, "Remove the background");
        assertSingleImageEdit("/matting2", imageUrl, "Cut out the main subject");
        assertSingleImageEdit("/enhance", imageUrl, "Upscale and enhance");

        ArgumentCaptor<ImageEditingCommand> commands =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, org.mockito.Mockito.times(3)).edit(commands.capture());
        assertArrayEquals(PNG_BYTES, commands.getAllValues().get(0).image().content());
        assertEquals("gpt-image-2", commands.getAllValues().get(0).model());
        assertNull(commands.getAllValues().get(0).mask());
    }

    @Test
    void sendsBinaryImageAndMaskToRemovalAndInpaint() throws Exception {
        stubEditedPng();

        mockMvc.perform(multipart(PREFIX + "/removal")
                        .file(png("image"))
                        .file(png("mask"))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("/api/v1/files/")))
                .andExpect(jsonPath("$.assetType").value("edit_result"));

        LlmModelEntity selectedModel = new LlmModelEntity(
                "seedream-4.0",
                "Seedream 4.0",
                "字节跳动",
                "huoshan/doubao-seedream-4-0-250828");
        selectedModel.setSiteId(siteId);
        selectedModel.setModelType(LlmModelEntity.TYPE_IMAGE);
        selectedModel = modelRepository.save(selectedModel);

        mockMvc.perform(multipart(PREFIX + "/inpaint")
                        .file(png("image"))
                        .file(png("mask"))
                        .param("prompt", "把遮挡物替换成蓝天")
                        .param("model", "seedream-4.0")
                        .param("size", "16x16")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("/api/v1/files/")))
                .andExpect(jsonPath("$.assetType").value("edit_result"));

        modelRepository.delete(selectedModel);

        mockMvc.perform(multipart(PREFIX + "/inpaint")
                        .file(png("image"))
                        .file(png("mask"))
                        .param("prompt", "把黑色区域自然补全")
                        .param("size", "16x16")
                        .param("outpaint", "true")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("/api/v1/files/")))
                .andExpect(jsonPath("$.assetType").value("edit_result"));

        ArgumentCaptor<ImageEditingCommand> commands =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, org.mockito.Mockito.times(3)).edit(commands.capture());

        ImageEditingCommand removal = commands.getAllValues().get(0);
        assertEquals("gpt-image-2", removal.model());
        assertArrayEquals(PNG_BYTES, removal.image().content());
        assertArrayEquals(MASK_BYTES, removal.mask().content());
        assertTrue(removal.prompt().startsWith(
                "Remove the masked object and reconstruct the background naturally."));
        assertTrue(removal.prompt().contains("transparent area of the provided mask"));

        ImageEditingCommand inpaint = commands.getAllValues().get(1);
        assertEquals("huoshan/doubao-seedream-4-0-250828", inpaint.model());
        assertTrue(inpaint.prompt().startsWith("把遮挡物替换成蓝天"));
        assertTrue(inpaint.prompt().contains("Keep every pixel in the opaque area unchanged"));
        assertArrayEquals(MASK_BYTES, inpaint.mask().content());
        assertEquals("16x16", inpaint.size());

        ImageEditingCommand outpaint = commands.getAllValues().get(2);
        assertEquals("gpt-image-2", outpaint.model());
        assertTrue(outpaint.prompt().startsWith("把黑色区域自然补全"));
        assertTrue(outpaint.prompt().contains("transparent area of the provided mask"));
        assertArrayEquals(MASK_BYTES, outpaint.mask().content());
        assertEquals("16x16", outpaint.size());

        verify(maskedImageCompositor, org.mockito.Mockito.times(1)).composite(
                any(byte[].class),
                any(byte[].class),
                any(byte[].class),
                any(MaskedImageCompositor.ImageDimensions.class));
        verify(maskedImageCompositor, org.mockito.Mockito.times(1))
                .validateGeneratedDimensions(
                        any(byte[].class),
                        any(MaskedImageCompositor.ImageDimensions.class));
    }

    @Test
    void rejectsInpaintSizesThatWouldBeSnappedByTheImageGateway() throws Exception {
        mockMvc.perform(multipart(PREFIX + "/inpaint")
                        .file(png("image"))
                        .file(png("mask"))
                        .param("prompt", "扩展画面")
                        .param("size", "1365x1024")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("的倍数")));

        verifyNoInteractions(imageEditingClient);
    }

    @Test
    void rejectsInpaintSizesThatDoNotMatchTheUploadedCanvas() throws Exception {
        mockMvc.perform(multipart(PREFIX + "/inpaint")
                        .file(png("image"))
                        .file(png("mask"))
                        .param("prompt", "扩展画面")
                        .param("size", "32x32")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        containsString("与原图一致")));

        verifyNoInteractions(imageEditingClient);
    }

    @Test
    void rejectsBlankInpaintPromptsBeforeCallingTheGateway() throws Exception {
        mockMvc.perform(multipart(PREFIX + "/inpaint")
                        .file(png("image"))
                        .file(png("mask"))
                        .param("prompt", "   ")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("提示词")));

        verifyNoInteractions(imageEditingClient);
    }

    @Test
    void rejectsImageEditingBeforeReadingUploadsOrCallingModelsWhenTheBalanceIsZero()
            throws Exception {
        // 抠图这条路径在解析模型之前先取账号级凭据，带 modelId 的重载在别的路径上；
        // 余额不足要在两条取法上都成立，否则测试只覆盖了一半。
        doThrow(new PaymentRequiredException("Insufficient account balance"))
                .when(relayCredentials).resolveImage(any(), any());
        doThrow(new PaymentRequiredException("Insufficient account balance"))
                .when(relayCredentials).resolveImage(any());

        mockMvc.perform(multipart(PREFIX + "/removal")
                        .file(png("image"))
                        .file(png("mask"))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        assertEquals(0, uploadedFileRepository.count());
        verifyNoInteractions(imageEditingClient, imageGenerationService, imageUrlDownloader);
    }

    /**
     * Regression guard: image endpoints must resolve the image credential, never the chat one.
     *
     * <p>With the New API relay disabled (local development) the two resolvers return different
     * static keys, and the gateway rejects an image model on the chat key with
     * {@code 404 model_not_found: "... is not available for this group"}. Every image entry point
     * therefore has to go through {@code resolveImage()}.</p>
     */
    @Test
    void imageEndpointsUseTheImageCredentialAndNeverTheChatOne() throws Exception {
        stubEditedPng();
        String imageUrl = uploadPng("source.png", "file");

        assertSingleImageEdit("/matting", imageUrl, "Remove the background");
        assertSingleImageEdit("/matting2", imageUrl, "Cut out the main subject");
        assertSingleImageEdit("/enhance", imageUrl, "Upscale and enhance");

        mockMvc.perform(multipart(PREFIX + "/removal")
                        .file(png("image"))
                        .file(png("mask"))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        mockMvc.perform(multipart(PREFIX + "/inpaint")
                        .file(png("image"))
                        .file(png("mask"))
                        .param("prompt", "把遮挡物替换成蓝天")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        ArgumentCaptor<ImageEditingCommand> commands =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, org.mockito.Mockito.times(5)).edit(commands.capture());
        commands.getAllValues().forEach(command ->
                assertEquals("sk-user-relay", command.credentials().apiKey(),
                        "image editing must not run on the chat credential"));
        verify(relayCredentials, never()).resolve(any(), any());
    }

    // ---------------------------------------------------------------- retouch

    @Test
    void retouchWithAReferenceImageUsesTheSupplierRouteCredentialsMaskAndNormalizedSize()
            throws Exception {
        LlmProviderEntity provider = new LlmProviderEntity(
                "smart-image-provider",
                "Smart image provider",
                "OPENAI",
                "https://supplier.example/v1",
                "/chat/completions",
                null,
                "BEARER",
                "",
                LlmProviderEntity.STATUS_ACTIVE,
                0);
        provider.setImagesPath("/images/edits");
        provider.setApiKey("supplier-image-key");
        provider = providerRepository.save(provider);

        LlmModelEntity image = new LlmModelEntity(
                "doubao-seedream-4-0-250828",
                "Seedream-4.0",
                "字节跳动",
                "huoshan/doubao-seedream-4-0-250828");
        image.setSiteId(siteId);
        image.setProviderId(provider.getId());
        image.setModelType(LlmModelEntity.TYPE_IMAGE);
        modelRepository.save(image);

        stubEditedPng();
        String imageUrl = uploadPng("reference.png", "file");

        mockMvc.perform(post(PREFIX + "/retouch")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{
                                    "role": "user",
                                    "content": [
                                      {"type": "text", "text": "把背景换成纯白色"},
                                      {"type": "image_url", "image_url": {"url": "%s"}}
                                    ]
                                  }],
                                  "model": "huoshan/doubao-seedream-4-0-250828",
                                  "modelKey": "doubao-seedream-4-0-250828",
                                  "size": "2k"
                                }
                                """.formatted(imageUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("把背景换成纯白色"))
                .andExpect(jsonPath("$.images", hasSize(1)))
                .andExpect(jsonPath("$.images[0].url").value(containsString("/api/v1/files/")))
                .andExpect(jsonPath("$.images[0].assetType").value("edit_result"));

        ArgumentCaptor<ImageEditingCommand> command =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient).edit(command.capture());
        assertEquals("huoshan/doubao-seedream-4-0-250828", command.getValue().model());
        assertEquals("把背景换成纯白色", command.getValue().prompt());
        assertEquals("2048x2048", command.getValue().size());
        assertEquals("sk-user-relay", command.getValue().credentials().apiKey());
        assertArrayEquals(PNG_BYTES, command.getValue().image().content());
        assertNull(command.getValue().mask());
    }

    @Test
    void retouchWithAReferenceImageRetriesAnUnpricedModelWithTheConfiguredDefault()
            throws Exception {
        when(imageEditingClient.edit(any()))
                .thenThrow(new LlmInvocationException(
                        "Image editing gateway returned HTTP 400: "
                                + "{\"code\":\"model_price_error\","
                                + "\"message\":\"Model gpt-image-2.5 has not been priced\"}"))
                .thenReturn(new ImageGenerationResult(
                        1700000001L,
                        "gpt-image-2",
                        List.of(new GeneratedImage(PNG_BASE64, null, null))));
        String imageUrl = uploadPng("reference.png", "file");

        mockMvc.perform(post(PREFIX + "/retouch")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{
                                    "role": "user",
                                    "content": [
                                      {"type": "text", "text": "把背景换成纯白色"},
                                      {"type": "image_url", "image_url": {"url": "%s"}}
                                    ]
                                  }],
                                  "modelKey": "gpt-image-2.5",
                                  "size": "1k"
                                }
                                """.formatted(imageUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(1)))
                .andExpect(jsonPath("$.images[0].url").value(containsString("/api/v1/files/")));

        ArgumentCaptor<ImageEditingCommand> commands =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, org.mockito.Mockito.times(2)).edit(commands.capture());
        assertEquals("gpt-image-2.5", commands.getAllValues().get(0).model());
        assertEquals("gpt-image-2", commands.getAllValues().get(1).model());
        assertArrayEquals(PNG_BYTES, commands.getAllValues().get(1).image().content());
    }

    @Test
    void retouchWithoutAReferenceImageUsesTheTextToImageService() throws Exception {
        when(imageGenerationService.generateWithRelayCredentials(any(), any(), any())).thenReturn(
                new ImageGenerationResponse(
                        Instant.parse("2026-10-16T06:00:00Z"),
                        "doubao-seedream-4-0-250828",
                        1,
                        List.of(new GeneratedImageResponse(
                                7L,
                                "/api/v1/files/7/content",
                                true,
                                FileAssetType.GENERATED,
                                "image/png",
                                4L,
                                "a clean product shot"))));

        mockMvc.perform(post(PREFIX + "/retouch")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "画一张白色杯子"}],
                                  "modelKey": "doubao-seedream-4-0-250828",
                                  "size": "1k"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("a clean product shot"))
                .andExpect(jsonPath("$.images[0].url").value("/api/v1/files/7/content"))
                .andExpect(jsonPath("$.images[0].assetType").value("generated"))
                .andExpect(jsonPath("$.images[0].size").value("1k"));

        ArgumentCaptor<ImageGenerationRequest> request =
                ArgumentCaptor.forClass(ImageGenerationRequest.class);
        verify(imageGenerationService).generateWithRelayCredentials(any(), request.capture(), any());
        assertEquals("画一张白色杯子", request.getValue().prompt());
        assertEquals("doubao-seedream-4-0-250828", request.getValue().model());
        assertEquals("1024x1024", request.getValue().size());
        verify(imageEditingClient, never()).edit(any());
    }

    @Test
    void rejectsRetouchWithoutAnyMessages() throws Exception {
        mockMvc.perform(post(PREFIX + "/retouch")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.messages").exists());

        verifyNoInteractions(imageEditingClient, imageGenerationService);
    }

    // ---------------------------------------------------------------- OpenAPI

    @Test
    void publishesTheSmartEditingImagePathsAndSchemasInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/models'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/files'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/matting'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/matting2'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/enhance'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/removal'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/retouch'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/inpaint'].post").exists())
                .andExpect(jsonPath("$.components.schemas.SmartEditPhotoModel").exists())
                .andExpect(jsonPath("$.components.schemas.SmartEditRetouchRequest").exists())
                .andExpect(jsonPath("$.components.schemas.SmartEditRetouchResponse").exists())
                .andExpect(jsonPath("$.components.schemas.SmartEditUploadedAsset").exists());
    }

    // ---------------------------------------------------------------- helpers

    private void stubEditedPng() {
        when(imageEditingClient.edit(any())).thenReturn(new ImageGenerationResult(
                1700000000L,
                "doubao-seedream-4-0-250828",
                List.of(new GeneratedImage(PNG_BASE64, null, null))));
    }

    private void assertSingleImageEdit(String path, String imageUrl, String promptFragment)
            throws Exception {
        mockMvc.perform(post(PREFIX + path)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"image_url":"%s"}""".formatted(imageUrl)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(containsString("/api/v1/files/")));

        ArgumentCaptor<ImageEditingCommand> command =
                ArgumentCaptor.forClass(ImageEditingCommand.class);
        verify(imageEditingClient, org.mockito.Mockito.atLeastOnce()).edit(command.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                command.getValue().prompt().contains(promptFragment),
                command.getValue().prompt());
    }

    private String uploadPng(String fileName, String fieldName) throws Exception {
        String body = mockMvc.perform(multipart(PREFIX + "/files")
                        .file(png(fieldName, fileName))
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        return response.get("url").asText();
    }

    private static MockMultipartFile png(String fieldName) {
        return png(fieldName, "image.png");
    }

    private static MockMultipartFile png(String fieldName, String fileName) {
        byte[] content = "mask".equals(fieldName) ? MASK_BYTES : PNG_BYTES;
        return new MockMultipartFile(fieldName, fileName, "image/png", content);
    }

    private static byte[] createPng(boolean mask) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(mask ? Color.WHITE : Color.GREEN);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        if (mask) {
            image.setRGB(0, 0, 0);
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("backend-smart-edit-image-test");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
