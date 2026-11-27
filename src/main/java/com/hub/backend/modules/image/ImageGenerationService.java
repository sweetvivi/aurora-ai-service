package com.hub.backend.modules.image;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.ImageGenerationClient;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageGenerationCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.image.dto.GeneratedImageResponse;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Text to image.
 *
 * <p>Generated images are written into the platform's own storage and registered as attachments of
 * the calling account. This applies to both inline base64 and gateway-hosted URLs: the latter are
 * downloaded immediately because their links are usually temporary. The response therefore uses the
 * same ownership-checked {@code /api/v1/files/{id}/content} endpoint as an upload and survives a
 * page refresh.</p>
 *
 * <p>If a hosted URL cannot be downloaded, the original URL is passed through with
 * {@code owned=false} rather than failing a generation that has already completed upstream.</p>
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    /**
     * 生成前预留的空间估算：4K PNG 实测约 3.8MB，留足余量按 20MB 算。
     * 只在「剩余空间连一张图都放不下」时提前拒绝，避免花了上游的钱却存不下图。
     */
    private static final long ESTIMATED_IMAGE_BYTES = 20L * 1024 * 1024;

    /**
     * 拆分多张时附在每条 prompt 后面的约束：否则模型会把「4 张」又画成一张四格拼图。
     */
    private static final String SINGLE_IMAGE_DIRECTIVE =
            "只输出一张完整图片，单幅画面，不要分格、不要拼接、不要把多张画面排在同一张图里。";

    private static final String PNG_EXTENSION = "png";
    private static final String PNG_MIME = "image/png";
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87A_SIGNATURE = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF89A_SIGNATURE = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] RIFF_SIGNATURE = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_SIGNATURE = {'W', 'E', 'B', 'P'};

    private final ImageGenerationClient imageGenerationClient;
    private final ImageUrlDownloader imageUrlDownloader;
    private final FileService fileService;
    private final ConversationService conversationService;
    private final ModelUseResolver modelResolver;
    private final UserRelayCredentialResolver relayCredentials;
    private final ImageModelFallbackService imageModelFallbackService;
    private final ImageGenerationRateLimiter imageRateLimiter;

    public ImageGenerationService(ImageGenerationClient imageGenerationClient,
                                  ImageUrlDownloader imageUrlDownloader,
                                  FileService fileService,
                                  ConversationService conversationService,
                                  ModelUseResolver modelResolver,
                                  UserRelayCredentialResolver relayCredentials,
                                  ImageModelFallbackService imageModelFallbackService,
                                  ImageGenerationRateLimiter imageRateLimiter) {
        this.imageGenerationClient = imageGenerationClient;
        this.imageUrlDownloader = imageUrlDownloader;
        this.fileService = fileService;
        this.conversationService = conversationService;
        this.modelResolver = modelResolver;
        this.relayCredentials = relayCredentials;
        this.imageModelFallbackService = imageModelFallbackService;
        this.imageRateLimiter = imageRateLimiter;
    }

    public ImageGenerationResponse generate(CurrentUser currentUser, ImageGenerationRequest request) {
        // 传 null 表示「按候选模型各自取」：分组是 Token 上的字段，图片模型常常只在另一个分组
        // （例如站点的「图片生成」分组）里有渠道，用一把账号级钥匙会得到「该分组无可用渠道」。
        // resolveImage 而非 resolve：关闭计费网关时它才回落到 app.llm.image-api-key。
        return generateWithRelayCredentials(currentUser, request, null);
    }

    /**
     * Runs with a relay credential that was already balance-checked by an owning orchestration
     * path. Keeps a single request from hitting the usage endpoint twice.
     *
     * @param credentials 固定凭据；{@code null} 表示按每个候选模型所属分组各自解析（并按需开通）
     */
    public ImageGenerationResponse generateWithRelayCredentials(
            CurrentUser currentUser,
            ImageGenerationRequest request,
            ProviderCredentials credentials) {
        // Resolving first is also what validates. The image model has a catalogue row of its own
        // (type IMAGE), so the same console manages the chat and the drawing keys, and a key
        // registered as a chat/video/audio model is refused with a 400 here rather than being sent
        // to an endpoint that answers with something opaque. When no row exists for the key the
        // call still goes through with the service defaults, which keeps a brand new image model
        // usable before anyone registers it.
        List<ModelUseResolver.Resolved> candidates = modelResolver.resolveAllForSite(
                currentUser.siteId(), request.model(), LlmModelEntity.TYPE_IMAGE);
        int requested = request.countOrDefault();
        // 归属来源：各页面复用同一个生图接口，只有这里能区分「这张图是谁产的」。
        FileSource source = resolveSource(request.source());

        // Validate before spending anything: a bad conversationId must not cost a generation.
        if (request.conversationId() != null) {
            conversationService.requireOwned(request.conversationId(), currentUser.userId());
        }

        // 配额按「张」扣，不是按请求：一次要 4 张就占 4 张额度，否则按请求计数等于把上限放大四倍。
        // 放在这里是因为它是所有文生图（图片生成页、绘画页、聊天、智能体、修图润色、画布）的唯一出口，
        // 计一次就够；图生图走的是各自的 edits 出口，在那里单独计。
        imageRateLimiter.checkAndRecord(currentUser, requested);
        // 顺序很重要：先花「配额」再花「钱」。空间不足时在这里就返回，不调用上游。
        // 一次要 N 张就按 N 张预留：只算一张会在存到一半时把后面的图丢掉。
        fileService.requireRoomFor(currentUser, ESTIMATED_IMAGE_BYTES * requested);

        java.util.function.Function<ModelUseResolver.Resolved, ProviderCredentials> credentialsFor =
                credentials != null
                        ? model -> credentials
                        : model -> relayCredentials.resolveImage(currentUser, model.id());
        List<GeneratedImage> produced = generateImages(
                currentUser.siteId(), candidates, request, requested, credentialsFor);

        List<GeneratedImageResponse> images = new ArrayList<>();
        for (GeneratedImage image : produced) {
            if (images.size() >= requested) {
                log.debug("Gateway returned more images than the {} requested, ignoring the rest", requested);
                break;
            }
            images.add(toResponse(currentUser, image, images.size(), request.conversationId(),
                    source, request.prompt(), candidates.get(0).requestKey()));
        }

        if (images.isEmpty()) {
            throw new LlmInvocationException("图片生成失败：模型没有返回可用图片，请重试");
        }

        return new ImageGenerationResponse(
                Instant.now(),
                candidates.get(0).requestKey(),
                images.size(),
                images);
    }

    /**
     * 一次要 N 张时，不把 N 直接丢给上游。
     *
     * <p>实测 gpt-image 系通道对 {@code n>1} 的处理是把 N 张画进同一张画布 —— 返回一张 2x2 拼图，
     * 数量上是 1 张。后端只收到一张图，用户却按 N 张付了钱，还失去了单张放大和单独下载。</p>
     *
     * <p>所以这里拆成 N 次「只出一张」的调用并发执行，再按顺序收回来：上游支不支持 n 都不影响
     * 结果的张数。单张指令同时写进 prompt，避免模型自己又画成一格一格的小图。</p>
     */
    private List<GeneratedImage> generateImages(Long siteId,
                                                List<ModelUseResolver.Resolved> candidates,
                                                ImageGenerationRequest request,
                                                int requested,
                                                java.util.function.Function<ModelUseResolver.Resolved,
                                                        ProviderCredentials> credentials) {
        if (requested <= 1) {
            return invokeImage(siteId, candidates, request, request.prompt().strip(), 1, credentials)
                    .value().images();
        }

        String prompt = request.prompt().strip() + "\n\n" + SINGLE_IMAGE_DIRECTIVE;
        ExecutorService pool = Executors.newFixedThreadPool(requested, runnable -> {
            Thread thread = new Thread(runnable, "image-generation");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<List<GeneratedImage>>> futures = new ArrayList<>(requested);
            for (int i = 0; i < requested; i++) {
                futures.add(pool.submit(() -> invokeImage(
                        siteId, candidates, request, prompt, 1, credentials).value().images()));
            }

            List<GeneratedImage> images = new ArrayList<>(requested);
            RuntimeException firstFailure = null;
            for (Future<List<GeneratedImage>> future : futures) {
                try {
                    List<GeneratedImage> one = future.get();
                    if (one == null || one.isEmpty()) {
                        continue;
                    }
                    images.add(one.get(0));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LlmInvocationException("图片生成被中断", interrupted);
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    log.warn("One of the {} parallel image calls failed: {}", requested,
                            cause == null ? "unknown" : cause.getMessage());
                    if (firstFailure == null) {
                        firstFailure = cause instanceof RuntimeException runtime
                                ? runtime
                                : new LlmInvocationException("图片生成失败：" + cause, cause);
                    }
                }
            }

            if (images.isEmpty() && firstFailure != null) {
                throw firstFailure;
            }
            if (images.size() < requested) {
                log.warn("Only {} of the {} requested images came back", images.size(), requested);
            }
            return images;
        } finally {
            pool.shutdownNow();
        }
    }

    private ImageModelFallbackService.InvocationResult<ImageGenerationResult> invokeImage(
            Long siteId,
            List<ModelUseResolver.Resolved> candidates,
            ImageGenerationRequest request,
            String prompt,
            int count,
            java.util.function.Function<ModelUseResolver.Resolved, ProviderCredentials>
                    credentials) {
        return imageModelFallbackService.invoke(
                siteId,
                candidates,
                selected -> imageGenerationClient.generate(new ImageGenerationCommand(
                        selected.upstreamKey(),
                        prompt,
                        request.sizeOrNull(),
                        request.qualityOrNull(),
                        count,
                        credentials.apply(selected))));
    }

    /**
     * Persists one image returned by an image-to-image or storyboard composite call.
     *
     * <p>The ordinary generation path owns this format detection and storage code. Exposing the
     * single-image step keeps AI Canvas from growing a second, subtly different implementation.</p>
     */
    public GeneratedImageResponse persistGeneratedImage(CurrentUser currentUser, GeneratedImage image,
                                                        int index, Long conversationId,
                                                        FileSource source, String prompt) {
        return persistGeneratedImage(currentUser, image, index, conversationId, source, prompt, null);
    }

    /**
     * Same as above, plus the model that produced the bytes.
     *
     * <p>图生图（参考图重绘、抠图、超清那些）走的是另一条上游出口，但落库结果和文生图一样出现在
     * 作品列表里。不记模型的话，这类卡片永远显示不出模型名 —— 用户看到的差别只是「有的图有名字，
     * 有的没有」，很难不把它当成 bug。</p>
     */
    public GeneratedImageResponse persistGeneratedImage(CurrentUser currentUser, GeneratedImage image,
                                                        int index, Long conversationId,
                                                        FileSource source, String prompt,
                                                        String model) {
        return toResponse(currentUser, image, index, conversationId, source, prompt, model);
    }

    /**
     * Client-declared origin of a generation.
     *
     * <p>The value only decides which page's work list shows the picture, so a client may declare
     * it; it grants nothing and hides nothing but its own rows. A blank value keeps the historical
     * default (the image-generation page), an unknown one is refused rather than silently ignored —
     * a typo would otherwise drop the result out of every list.</p>
     */
    private static FileSource resolveSource(String declared) {
        if (declared == null || declared.isBlank()) {
            return FileSource.IMAGE_GENERATION;
        }
        FileSource source = FileSource.fromValueOrNull(declared);
        if (source == null) {
            throw new InvalidRequestException("source 取值非法：" + declared);
        }
        return source;
    }

    /**
     * Stores one generated image and shapes the API view of it.
     *
     * <p>Inline base64 is decoded directly. A hosted URL is downloaded first. The former temporary
     * URL is returned only when the download fails, so a completed upstream generation still has a
     * usable response.</p>
     */
    private GeneratedImageResponse toResponse(CurrentUser currentUser, GeneratedImage image,
                                              int index, Long conversationId, FileSource source,
                                              String prompt, String model) {
        byte[] bytes;
        String mimeTypeHint = null;
        if (image.hasInlineData()) {
            bytes = decode(image.base64());
        } else {
            try {
                DownloadedImage downloaded = imageUrlDownloader.download(image.url());
                bytes = downloaded.content();
                mimeTypeHint = downloaded.mimeType();
            } catch (LlmInvocationException exception) {
                log.warn("Unable to persist a hosted generated image; returning its upstream URL "
                        + "instead: {}", exception.getMessage());
                return GeneratedImageResponse.hosted(image.url(), image.revisedPrompt());
            }
        }

        ImageFormat format = detectFormat(bytes, mimeTypeHint);
        String name = "generated-%d.%s".formatted(index + 1, format.extension());
        UploadedFileResponse stored = fileService.storeGenerated(
                currentUser, bytes, format.extension(), format.mimeType(), name,
                FileAssetType.GENERATED, source, conversationId, prompt, model);
        return GeneratedImageResponse.stored(stored, image.revisedPrompt());
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException exception) {
            throw new LlmInvocationException("图片生成失败：模型返回的数据无法解析，请重试");
        }
    }

    private static ImageFormat detectFormat(byte[] content, String mimeTypeHint) {
        if (content == null || content.length == 0) {
            throw new LlmInvocationException("图片生成失败：模型没有返回图片，请重试");
        }
        if (startsWith(content, PNG_SIGNATURE)) {
            return new ImageFormat(PNG_EXTENSION, PNG_MIME);
        }
        if (startsWith(content, JPEG_SIGNATURE)) {
            return new ImageFormat("jpg", "image/jpeg");
        }
        if (startsWith(content, GIF87A_SIGNATURE) || startsWith(content, GIF89A_SIGNATURE)) {
            return new ImageFormat("gif", "image/gif");
        }
        if (startsWith(content, RIFF_SIGNATURE) && startsAt(content, 8, WEBP_SIGNATURE)) {
            return new ImageFormat("webp", "image/webp");
        }
        if (content.length >= 2 && content[0] == 'B' && content[1] == 'M') {
            return new ImageFormat("bmp", "image/bmp");
        }

        String normalized = mimeTypeHint == null
                ? ""
                : mimeTypeHint.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case PNG_MIME -> new ImageFormat(PNG_EXTENSION, PNG_MIME);
            case "image/jpg", "image/jpeg" -> new ImageFormat("jpg", "image/jpeg");
            case "image/gif" -> new ImageFormat("gif", "image/gif");
            case "image/webp" -> new ImageFormat("webp", "image/webp");
            case "image/bmp" -> new ImageFormat("bmp", "image/bmp");
            default -> throw new LlmInvocationException(
                    "图片生成失败：不支持的图片格式 " + normalized);
        };
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return startsAt(content, 0, signature);
    }

    private static boolean startsAt(byte[] content, int offset, byte[] signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private record ImageFormat(String extension, String mimeType) {
    }
}
