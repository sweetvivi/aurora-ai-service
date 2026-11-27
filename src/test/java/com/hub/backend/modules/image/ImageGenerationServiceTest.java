package com.hub.backend.modules.image;

import com.hub.backend.infra.llm.ImageGenerationClient;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageGenerationCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多张 = 多次单图调用。
 *
 * <p>回归背景：上游对 {@code n>1} 不是返 N 张，而是把 N 张画进一张 2x2 拼图。后端按返回数量落库，
 * 用户要 4 张却只拿到 1 张图、还无法单张放大。这条测试锁住「要 4 张就发 4 次 n=1」的行为。</p>
 */
class ImageGenerationServiceTest {

    private static final String PNG_BASE64 = "iVBORw0KGgo=";
    private static final CurrentUser USER = new CurrentUser(5L, 1L, "yang");

    private ImageGenerationClient client;
    private FileService fileService;
    private ImageModelFallbackService fallback;
    private ImageGenerationService service;

    @BeforeEach
    void setUp() {
        client = mock(ImageGenerationClient.class);
        fileService = mock(FileService.class);
        fallback = mock(ImageModelFallbackService.class);
        ModelUseResolver resolver = mock(ModelUseResolver.class);

        ModelUseResolver.Resolved resolved = new ModelUseResolver.Resolved(
                30L, "gpt-image-2.5", "gpt-image-2.5", LlmModelEntity.TYPE_IMAGE,
                true, false, null, false, null, null);
        when(resolver.resolveAllForSite(anyLong(), any(), any())).thenReturn(List.of(resolved));

        when(client.generate(any())).thenReturn(
                new ImageGenerationResult(1_700_000_000L, "gpt-image-2.5",
                        List.of(new GeneratedImage(PNG_BASE64, null, null))));

        UploadedFileResponse stored = mock(UploadedFileResponse.class);
        when(stored.id()).thenReturn(112L);
        when(stored.url()).thenReturn("/api/v1/files/112/content");
        when(stored.assetType()).thenReturn(FileAssetType.GENERATED);
        when(stored.mimeType()).thenReturn("image/png");
        when(stored.sizeBytes()).thenReturn(2_048L);
        when(fileService.storeGenerated(any(), any(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), anyString())).thenReturn(stored);

        // 回退服务在真实实现里只是「按顺序试候选」，这里直接执行第一个候选的调用。
        when(fallback.invoke(anyLong(), anyList(), any())).thenAnswer(invocation -> {
            Function<ModelUseResolver.Resolved, Object> call = invocation.getArgument(2);
            Object value = call.apply(resolved);
            return new ImageModelFallbackService.InvocationResult<>(resolved, value, false);
        });

        service = new ImageGenerationService(
                client,
                mock(ImageUrlDownloader.class),
                fileService,
                mock(ConversationService.class),
                resolver,
                mock(UserRelayCredentialResolver.class),
                fallback,
                mock(ImageGenerationRateLimiter.class));
    }

    @Test
    void fourImagesAreFourSingleImageCalls() {
        ImageGenerationResponse response = service.generateWithRelayCredentials(
                USER, request(4), mock(ProviderCredentials.class));

        assertEquals(4, response.count());
        assertEquals(4, response.images().size());

        ArgumentCaptor<ImageGenerationCommand> commands = ArgumentCaptor.forClass(ImageGenerationCommand.class);
        verify(client, times(4)).generate(commands.capture());
        assertTrue(commands.getAllValues().stream().allMatch(command -> command.count() == 1),
                "每次调用只申请一张，避免上游拼成 2x2");
        assertTrue(commands.getAllValues().stream()
                        .allMatch(command -> command.prompt().contains("只输出一张完整图片")),
                "prompt 要显式禁止分格拼接");
    }

    @Test
    void singleImageKeepsTheOriginalPrompt() {
        service.generateWithRelayCredentials(USER, request(1), mock(ProviderCredentials.class));

        ArgumentCaptor<ImageGenerationCommand> commands = ArgumentCaptor.forClass(ImageGenerationCommand.class);
        verify(client, times(1)).generate(commands.capture());
        assertEquals(1, commands.getValue().count());
        assertEquals("一位时尚都市女性", commands.getValue().prompt());
    }

    @Test
    void keepsTheImagesThatDidComeBackWhenOneCallFails() {
        when(client.generate(any()))
                .thenReturn(new ImageGenerationResult(1L, "gpt-image-2.5",
                        List.of(new GeneratedImage(PNG_BASE64, null, null))))
                .thenThrow(new RuntimeException("upstream boom"))
                .thenReturn(new ImageGenerationResult(1L, "gpt-image-2.5",
                        List.of(new GeneratedImage(PNG_BASE64, null, null))))
                .thenReturn(new ImageGenerationResult(1L, "gpt-image-2.5",
                        List.of(new GeneratedImage(PNG_BASE64, null, null))));

        ImageGenerationResponse response = service.generateWithRelayCredentials(
                USER, request(4), mock(ProviderCredentials.class));

        assertEquals(3, response.count());
    }

    private static ImageGenerationRequest request(int count) {
        return new ImageGenerationRequest("一位时尚都市女性", "gpt-image-2.5", "1024x1024",
                null, count, null, null);
    }
}
