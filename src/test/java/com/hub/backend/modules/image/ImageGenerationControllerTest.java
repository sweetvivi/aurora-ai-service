package com.hub.backend.modules.image;

import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.config.properties.RateLimitProperties;
import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.common.ratelimit.InMemoryRateLimiter;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.ImageGenerationClient;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageGenerationCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Image generation through the HTTP layer, with the gateway replaced by a stub.
 *
 * <p>The live gateway currently answers image requests with an upstream tool error, so the wire
 * contract is verified here instead; see {@code docs/image-api.md}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImageGenerationControllerTest extends AuthenticatedControllerTestSupport {

    private static final String GENERATIONS_URL = "/api/v1/images/generations";
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG_BYTES);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UploadedFileRepository uploadedFileRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private StorageProperties storageProperties;

    @MockitoBean
    private ImageGenerationClient imageGenerationClient;

    @MockitoBean
    private ImageUrlDownloader imageUrlDownloader;

    @MockitoBean
    private UserRelayCredentialResolver relayCredentials;

    private Long siteId;
    private Long userId;
    private Long conversationId;
    private String userToken;

    @BeforeEach
    void seed() {
        uploadedFileRepository.deleteAll();
        conversationRepository.deleteAll();
        userAccountRepository.deleteAll();
        modelRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        siteId = site.getId();
        userId = userAccountRepository
                .save(new UserAccountEntity(site.getId(), "alice", "Alice")).getId();
        userToken = accessToken(userId);
        when(relayCredentials.resolveImage(any(), any())).thenReturn(new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-user-relay",
                null,
                null,
                null));
        conversationId = conversationRepository
                .save(new ConversationEntity(userId, null, "既有会话")).getId();

        // Default image model must be catalogued: allow-unlisted-models is off.
        LlmModelEntity imageDefault = new LlmModelEntity(
                "gpt-image-2", "GPT Image 2", "OpenAI", null);
        imageDefault.setModelType(LlmModelEntity.TYPE_IMAGE);
        imageDefault.setSiteId(siteId);
        modelRepository.save(imageDefault);
    }

    private void stubGateway(GeneratedImage... images) {
        when(imageGenerationClient.generate(any())).thenReturn(
                new ImageGenerationResult(1700000000L, "gpt-image-2", List.of(images)));
    }

    @Test
    void refusesALanguageModelOnTheImagePath() throws Exception {
        // Visible and enabled — it simply cannot draw. Without the type check this key was forwarded
        // to the image endpoint, which answered with an opaque upstream error.
        LlmModelEntity chat = new LlmModelEntity(
                "deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek", null);
        chat.setModelType(LlmModelEntity.TYPE_CHAT);
        chat.setSiteId(siteId);
        modelRepository.save(chat);

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","model":"deepseek-v4.1-flash"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请求参数有误")));

        // refused before spending anything, so a wrong model key costs no generation
        org.mockito.Mockito.verifyNoInteractions(imageGenerationClient);
    }

    @Test
    void rejectsAnImageModelThatIsNotInTheCatalogue() throws Exception {
        // app.llm.allow-unlisted-models defaults to false: unregistered keys must not hit upstream
        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","model":"brand-new-image-model"}"""))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verifyNoInteractions(imageGenerationClient);
    }

    @Test
    void storesInlineImagesAsAttachmentsOfTheCaller() throws Exception {
        stubGateway(new GeneratedImage(PNG_BASE64, null, "一只戴帽子的猫"));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","size":"1024x1024","count":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-image-2"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.images[0].owned").value(true))
                .andExpect(jsonPath("$.images[0].assetType").value("generated"))
                .andExpect(jsonPath("$.images[0].fileId").isNumber())
                .andExpect(jsonPath("$.images[0].mimeType").value("image/png"))
                .andExpect(jsonPath("$.images[0].revisedPrompt").value("一只戴帽子的猫"))
                .andExpect(jsonPath("$.images[0].url").value(
                        org.hamcrest.Matchers.startsWith("/api/v1/files/")));

        // the stored bytes are reachable through the ordinary, ownership checked content endpoint
        String url = uploadedFileRepository.findAll().get(0).getId().toString();
        mockMvc.perform(get("/api/v1/files/" + url + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk());
    }

    @Test
    void refusesToSpendMorePicturesThanTheAccountWindowAllows() throws Exception {
        // Images are the only action that bills upstream per click. Without this limit one logged in
        // account can loop the endpoint and spend without bound; the balance gate only stops at zero.
        int quota = rateLimitProperties.getImage().getMaxImagesPerUser();
        rateLimiter.tryAcquire(
                ImageGenerationRateLimiter.KEY_PREFIX + userId, quota, quota,
                rateLimitProperties.getImage().getWindow());

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","count":1}"""))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("出图太频繁")));

        // The request is refused before any upstream call: a rejected picture must not be paid for.
        verifyNoInteractions(imageGenerationClient);
    }

    @Test
    void refusesToStartAGenerationWhenTheAccountHasNoRoomLeft() throws Exception {
        // The bytes only exist after the gateway has been paid for, so the room check has to run
        // before the call. Moving it after the upstream call still passes every functional test
        // while quietly spending money on a picture the account has nowhere to keep.
        long quota = storageProperties.getMaxUserBytes().toBytes();
        uploadedFileRepository.save(new UploadedFileEntity(
                userId, null, "2026/11/02/filler.png", "filler.png", "image/png", "png",
                FileAssetType.GENERATED, FileSource.IMAGE_GENERATION, quota, "f".repeat(64)));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","size":"1024x1024","count":1}"""))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("存储空间不足")));

        verifyNoInteractions(imageGenerationClient);
    }

    @Test
    void recordsTheDeclaredSourceSoOnlyTheOwningPageListsTheImage() throws Exception {
        // Every page reuses this endpoint; without the source the drawing gallery could not tell its
        // own output from the image-generation page's and listed both.
        stubGateway(new GeneratedImage(PNG_BASE64, null, "一只戴帽子的猫"));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","size":"1024x1024","count":1,"source":"painting"}"""))
                .andExpect(status().isOk());

        assertEquals(FileSource.PAINTING, uploadedFileRepository.findAll().get(0).getSource());
    }

    @Test
    void defaultsTheSourceToTheImageGenerationPage() throws Exception {
        stubGateway(new GeneratedImage(PNG_BASE64, null, "一只戴帽子的猫"));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","size":"1024x1024","count":1}"""))
                .andExpect(status().isOk());

        assertEquals(FileSource.IMAGE_GENERATION, uploadedFileRepository.findAll().get(0).getSource());
    }

    @Test
    void refusesAnUnknownSourceBeforeSpendingAnything() throws Exception {
        // A typo must fail loudly: silently keeping it would drop the picture out of every work list.
        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","size":"1024x1024","source":"paintings"}"""))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(imageGenerationClient);
    }

    @Test
    void downloadsAndStoresHostedImagesAsAttachmentsOfTheCaller() throws Exception {
        String hostedUrl = "https://cdn.example.com/a.png";
        when(imageUrlDownloader.download(hostedUrl))
                .thenReturn(new DownloadedImage(PNG_BYTES, "image/png"));
        stubGateway(new GeneratedImage(null, hostedUrl, "a blue circle"));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","conversationId":%d}""".formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].owned").value(true))
                .andExpect(jsonPath("$.images[0].fileId").isNumber())
                .andExpect(jsonPath("$.images[0].mimeType").value("image/png"))
                .andExpect(jsonPath("$.images[0].revisedPrompt").value("a blue circle"))
                .andExpect(jsonPath("$.images[0].url").value(
                        org.hamcrest.Matchers.startsWith("/api/v1/files/")));

        assertEquals(conversationId, uploadedFileRepository.findAll().get(0).getConversationId());
        String fileId = uploadedFileRepository.findAll().get(0).getId().toString();
        mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$.items[0].assetType").value("generated"))
                .andExpect(jsonPath("$.items[0].mimeType").value("image/png"));
    }

    @Test
    void keepsTheHostedUrlWhenDownloadingItFails() throws Exception {
        String hostedUrl = "https://cdn.example.com/a.png";
        when(imageUrlDownloader.download(hostedUrl))
                .thenThrow(new LlmInvocationException("temporary CDN failure"));
        stubGateway(new GeneratedImage(null, hostedUrl, null));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].owned").value(false))
                .andExpect(jsonPath("$.images[0].assetType").value("generated"))
                .andExpect(jsonPath("$.images[0].fileId").doesNotExist())
                .andExpect(jsonPath("$.images[0].url").value(hostedUrl));
    }

    @Test
    void forwardsTheRequestedOptionsToTheGateway() throws Exception {
        stubGateway(new GeneratedImage(PNG_BASE64, null, null));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","size":"1024x1024","quality":"high","count":2}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<ImageGenerationCommand> command =
                ArgumentCaptor.forClass(ImageGenerationCommand.class);
        // count=2 不再透传成 n=2：上游会把它画成一张两格拼图，所以拆成两次单图调用。
        verify(imageGenerationClient, times(2)).generate(command.capture());
        assertEquals("gpt-image-2", command.getAllValues().get(0).model());
        assertEquals("1024x1024", command.getAllValues().get(0).size());
        assertEquals("high", command.getAllValues().get(0).quality());
        assertEquals(1, command.getAllValues().get(0).count());
        assertEquals(1, command.getAllValues().get(1).count());
    }

    @Test
    void retriesAnUnpricedImageModelWithTheConfiguredDefault() throws Exception {
        LlmModelEntity primary = new LlmModelEntity(
                "gpt-image-2.5", "GPT Image 2.5", "OpenAI", null);
        primary.setModelType(LlmModelEntity.TYPE_IMAGE);
        primary.setSiteId(siteId);
        modelRepository.save(primary);

        when(imageGenerationClient.generate(any()))
                .thenThrow(new LlmInvocationException(
                        "Image gateway returned HTTP 400: "
                                + "{\"code\":\"model_price_error\","
                                + "\"message\":\"Model gpt-image-2.5 has not been priced\"}"))
                .thenReturn(new ImageGenerationResult(
                        1700000000L,
                        "gpt-image-2",
                        List.of(new GeneratedImage(PNG_BASE64, null, null))));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"一只戴帽子的猫","model":"gpt-image-2.5"}"""))
                .andExpect(status().isOk())
                // The response echoes the public request key; the concrete fallback route stays
                // internal even though it was the second upstream attempt.
                .andExpect(jsonPath("$.model").value("gpt-image-2.5"));

        ArgumentCaptor<ImageGenerationCommand> commands =
                ArgumentCaptor.forClass(ImageGenerationCommand.class);
        verify(imageGenerationClient, org.mockito.Mockito.times(2)).generate(commands.capture());
        assertEquals("gpt-image-2.5", commands.getAllValues().get(0).model());
        assertEquals("gpt-image-2", commands.getAllValues().get(1).model());
    }

    @Test
    void sendsTheConfiguredRouteButEchoesThePublicModelKey() throws Exception {
        LlmModelEntity image = new LlmModelEntity(
                "seedream-4", "Seedream 4", "ByteDance", "hoshan/seedream-4-0");
        image.setModelType(LlmModelEntity.TYPE_IMAGE);
        modelRepository.save(image);
        when(imageGenerationClient.generate(any())).thenReturn(new ImageGenerationResult(
                1700000000L, null, List.of(new GeneratedImage(PNG_BASE64, null, null))));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","model":"seedream-4"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("seedream-4"));

        ArgumentCaptor<ImageGenerationCommand> command =
                ArgumentCaptor.forClass(ImageGenerationCommand.class);
        verify(imageGenerationClient).generate(command.capture());
        assertEquals("hoshan/seedream-4-0", command.getValue().model(),
                "the supplier route, not the public key, must reach the gateway");
    }

    @Test
    void neverReturnsMoreImagesThanRequested() throws Exception {
        stubGateway(
                new GeneratedImage(PNG_BASE64, null, null),
                new GeneratedImage(PNG_BASE64, null, null),
                new GeneratedImage(PNG_BASE64, null, null));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","count":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.images", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void attachesTheResultToAConversationWhenAsked() throws Exception {
        stubGateway(new GeneratedImage(PNG_BASE64, null, null));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","conversationId":%d}""".formatted(conversationId)))
                .andExpect(status().isOk());

        assertEquals(conversationId, uploadedFileRepository.findAll().get(0).getConversationId());
    }

    @Test
    void rejectsAConversationOwnedBySomebodyElse() throws Exception {
        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","conversationId":999999}"""))
                .andExpect(status().isNotFound());

        verify(imageGenerationClient, org.mockito.Mockito.never()).generate(any());
    }

    @Test
    void rejectsABlankPrompt() throws Exception {
        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.prompt").exists());
    }

    @Test
    void rejectsGenerationBeforeWritingAttachmentsWhenTheUserBalanceIsZero() throws Exception {
        doThrow(new PaymentRequiredException("Insufficient account balance"))
                .when(relayCredentials).resolveImage(any(), any());

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle"}"""))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        assertEquals(0, uploadedFileRepository.count());
        verifyNoInteractions(imageGenerationClient, imageUrlDownloader);
    }

    @Test
    void rejectsAnUnsupportedQuality() throws Exception {
        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","quality":"ultra"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.quality").exists());
    }

    @Test
    void rejectsTooManyImages() throws Exception {
        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle","count":9}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.count").exists());
    }

    @Test
    void mapsGatewayFailureToBadGateway() throws Exception {
        when(imageGenerationClient.generate(any())).thenThrow(
                new LlmInvocationException("Image gateway returned HTTP 404: model not available"));

        mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle"}"""))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void publishesTheImagePathInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/images/generations'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ImageGenerationRequest").exists())
                .andExpect(jsonPath("$.components.schemas.GeneratedImage").exists());
    }

    @Test
    void storedImageIsNotVisibleToAnotherAccount() throws Exception {
        stubGateway(new GeneratedImage(PNG_BASE64, null, null));
        String body = mockMvc.perform(post(GENERATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"a blue circle"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotNull(body);

        Long fileId = uploadedFileRepository.findAll().get(0).getId();
        Long otherUser = userAccountRepository
                .save(new UserAccountEntity(appSiteRepository.findAll().get(0).getId(), "bob", "Bob")).getId();

        mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(otherUser)))
                .andExpect(status().isNotFound());
    }
}
