package com.hub.backend.modules.smartedit;

import com.fasterxml.jackson.databind.JsonNode;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.infra.llm.ImageEditingClient;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageEditFile;
import com.hub.backend.infra.llm.dto.ImageEditingCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.image.ImageGenerationRateLimiter;
import com.hub.backend.modules.image.ImageGenerationService;
import com.hub.backend.modules.image.ImageModelFallbackService;
import com.hub.backend.modules.image.dto.GeneratedImageResponse;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import com.hub.backend.modules.model.ModelCatalogService;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.dto.ModelSummaryResponse;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import com.hub.backend.modules.smartedit.dto.PhotoDrawResponse;
import com.hub.backend.modules.smartedit.dto.PhotoDpiResponse;
import com.hub.backend.modules.smartedit.dto.PhotoModelFlagsResponse;
import com.hub.backend.modules.smartedit.dto.PhotoModelResponse;
import com.hub.backend.modules.smartedit.dto.RetouchImageResponse;
import com.hub.backend.modules.smartedit.dto.RetouchRequest;
import com.hub.backend.modules.smartedit.dto.RetouchResponse;
import com.hub.backend.modules.smartedit.dto.UploadedAssetResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Image operations used by the smart-editing board.
 */
@Service
public class SmartEditImageService {

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    /** Upper bound of reference images for one composition request. */
    private static final int MAX_COMPOSE_IMAGES = 4;
    private static final String PNG_MIME = "image/png";
    private static final String JPEG_MIME = "image/jpeg";
    private static final String WEBP_MIME = "image/webp";
    private static final int IMAGE_SIZE_MULTIPLE = 16;
    private static final int MIN_IMAGE_SIDE = 16;
    private static final int MAX_IMAGE_WIDTH = 3840;
    private static final int MAX_IMAGE_HEIGHT = 2160;
    private static final Pattern IMAGE_SIZE_PATTERN =
            Pattern.compile("^(\\d{2,4})x(\\d{2,4})$");
    private static final String MASK_BOUNDARY_INSTRUCTION = """

            Apply the requested change only inside the transparent area of the provided mask.
            Keep every pixel in the opaque area unchanged. Do not replace, redraw, extend, or add
            any content outside the transparent area.""";
    private static final Pattern PLATFORM_FILE_URL =
            Pattern.compile("(?:^|/)api/v1/files/(\\d+)/content/?$");

    private static final List<String> DEFAULT_SIZES = List.of(
            "1024x1024", "1536x1024", "1024x1536", "2048x2048");
    private static final List<PhotoDpiResponse> DEFAULT_DPI = List.of(
            new PhotoDpiResponse("标准1K", "1k", 0),
            new PhotoDpiResponse("高清2K", "2k", 0),
            new PhotoDpiResponse("超清4K", "4k", 0));

    private final ModelCatalogService modelCatalogService;
    private final ModelUseResolver modelResolver;
    private final ImageEditingClient imageEditingClient;
    private final ImageGenerationService imageGenerationService;
    private final ImageUrlDownloader imageUrlDownloader;
    private final FileService fileService;
    private final UserRelayCredentialResolver relayCredentials;
    private final MaskedImageCompositor maskedImageCompositor;
    private final ImageModelFallbackService imageModelFallbackService;
    private final ImageGenerationRateLimiter imageRateLimiter;

    public SmartEditImageService(ModelCatalogService modelCatalogService,
                                 ModelUseResolver modelResolver,
                                 ImageEditingClient imageEditingClient,
                                 ImageGenerationService imageGenerationService,
                                 ImageUrlDownloader imageUrlDownloader,
                                 FileService fileService,
                                 UserRelayCredentialResolver relayCredentials,
                                 MaskedImageCompositor maskedImageCompositor,
                                 ImageModelFallbackService imageModelFallbackService,
                                 ImageGenerationRateLimiter imageRateLimiter) {
        this.modelCatalogService = modelCatalogService;
        this.modelResolver = modelResolver;
        this.imageEditingClient = imageEditingClient;
        this.imageGenerationService = imageGenerationService;
        this.imageUrlDownloader = imageUrlDownloader;
        this.fileService = fileService;
        this.relayCredentials = relayCredentials;
        this.maskedImageCompositor = maskedImageCompositor;
        this.imageModelFallbackService = imageModelFallbackService;
        this.imageRateLimiter = imageRateLimiter;
    }

    @Transactional(readOnly = true)
    public List<PhotoModelResponse> models(Long siteId) {
        return modelCatalogService.catalog(siteId, null, LlmModelEntity.TYPE_IMAGE).models().stream()
                .map(summary -> toPhotoModel(summary, summary.modelKey()))
                .toList();
    }

    @Transactional
    public UploadedAssetResponse upload(CurrentUser currentUser, MultipartFile file, String save) {
        validateSaveValue(save);
        validateUpload(file);
        UploadedFileResponse stored = fileService.upload(
                currentUser, file, null, FileAssetType.REFERENCE);
        return new UploadedAssetResponse(
                stored.url(), String.valueOf(stored.id()), stored.assetType());
    }

    public ImageUrlResponse matting(CurrentUser currentUser, String imageUrl) {
        return editUrl(currentUser, imageUrl,
                "Remove the background and keep only the main subject. Return a clean cut-out image.");
    }

    public ImageUrlResponse matting2(CurrentUser currentUser, String imageUrl) {
        return editUrl(currentUser, imageUrl,
                "Cut out the main subject with precise edges and remove the background completely.");
    }

    public ImageUrlResponse enhance(CurrentUser currentUser, String imageUrl) {
        return editUrl(currentUser, imageUrl,
                "Upscale and enhance this image. Preserve its content while improving clarity, detail and sharpness.");
    }

    public ImageUrlResponse removal(CurrentUser currentUser, MultipartFile image, MultipartFile mask) {
        ProviderCredentials credentials = imageCredentials(currentUser);
        ImageEditFile source = editFile(image, "image");
        ImageEditFile maskFile = editFile(mask, "mask");
        return editAndStoreOne(currentUser, resolveDefaultModel(currentUser.siteId()),
                "Remove the masked object and reconstruct the background naturally.", source, maskFile,
                null, credentials, true);
    }

    public ImageUrlResponse inpaint(CurrentUser currentUser,
                                    MultipartFile image,
                                    MultipartFile mask,
                                    String prompt,
                                    String modelKey,
                                    String size,
                                    boolean outpaint) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidRequestException("提示词不能为空");
        }
        if (prompt.length() > 4000) {
            throw new InvalidRequestException("提示词最多 4000 个字符");
        }

        ModelUseResolver.Resolved model = modelResolver.resolveForSite(
                currentUser.siteId(), modelKey, LlmModelEntity.TYPE_IMAGE);
        ProviderCredentials credentials = imageCredentials(currentUser, model.id());
        ImageEditFile source = editFile(image, "image");
        ImageEditFile maskFile = editFile(mask, "mask");
        return editAndStoreOne(currentUser, model,
                prompt.strip(), source, maskFile, normalizeSize(size), credentials, !outpaint);
    }

    /**
     * Multi-image composition: send 1-4 reference images plus a prompt to the editing endpoint.
     *
     * <p>E-commerce tools need this shape — a product photo plus a background / pattern / model
     * reference. The single-image {@code inpaint} path cannot express it, while the underlying
     * {@link ImageEditingCommand} already carries a list.</p>
     */
    public ImageUrlResponse compose(CurrentUser currentUser,
                                    List<MultipartFile> images,
                                    String prompt,
                                    String modelKey,
                                    String size) {
        return compose(currentUser, images, prompt, modelKey, size, FileSource.SMART_EDIT, null);
    }

    /**
     * 同一套图像编辑能力，但产出归属由调用方指定。
     *
     * <p>工具库（`/painting` 的图片创作）与电商工具走的是这一条链路，产出的图却该分别
     * 出现在绘画作品与电商历史里。归属如果写死成 `smart_edit`，用户从绘画页点的抠图会
     * 跑到「修图成果」里去 —— 和之前修过的「绘画广场混入别页的图」是同一个毛病。</p>
     */
    public ImageUrlResponse compose(CurrentUser currentUser,
                                    List<MultipartFile> images,
                                    String prompt,
                                    String modelKey,
                                    String size,
                                    FileSource fileSource) {
        return compose(currentUser, images, prompt, modelKey, size, fileSource, null);
    }

    /**
     * Same as {@link #compose(CurrentUser, List, String, String, String, FileSource)} plus an
     * optional conversation id so chat edit results stay attached to the thread (focus image).
     */
    public ImageUrlResponse compose(CurrentUser currentUser,
                                    List<MultipartFile> images,
                                    String prompt,
                                    String modelKey,
                                    String size,
                                    FileSource fileSource,
                                    Long conversationId) {
        if (images == null || images.isEmpty()) {
            throw new InvalidRequestException("请至少上传一张图片");
        }
        if (images.size() > MAX_COMPOSE_IMAGES) {
            throw new InvalidRequestException(
                    "最多支持 %d 张图片".formatted(MAX_COMPOSE_IMAGES));
        }
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidRequestException("提示词不能为空");
        }
        if (prompt.length() > 4000) {
            throw new InvalidRequestException("提示词最多 4000 个字符");
        }

        List<ImageEditFile> files = new ArrayList<>();
        for (int index = 0; index < images.size(); index++) {
            files.add(editFile(images.get(index), "image" + (index + 1)));
        }

        ModelUseResolver.Resolved model = modelResolver.resolveForSite(
                currentUser.siteId(), modelKey, LlmModelEntity.TYPE_IMAGE);

        ImageGenerationResult result = imageModelFallbackService.invoke(
                        currentUser.siteId(),
                        model,
                        selected -> imageEditingClient.edit(new ImageEditingCommand(
                                selected.upstreamKey(), prompt.strip(), files, null, size,
                                imageCredentials(currentUser, selected.id()))))
                .value();
        if (result.images().isEmpty()) {
            throw new LlmInvocationException("图片编辑失败：模型没有返回可用图片，请重试");
        }
        return new ImageUrlResponse(
                storeGenerated(currentUser, result.images().get(0), 0, fileSource, conversationId,
                        prompt, model.requestKey()),
                FileAssetType.EDIT_RESULT);
    }

    public RetouchResponse retouch(CurrentUser currentUser, RetouchRequest request) {
        RetouchPrompt parsed = extractPrompt(request.messages());
        ModelUseResolver.Resolved model = modelResolver.resolveForSite(
                currentUser.siteId(),
                request.modelKey() == null || request.modelKey().isBlank()
                        ? request.model() : request.modelKey(),
                LlmModelEntity.TYPE_IMAGE);
        ProviderCredentials credentials = imageCredentials(currentUser, model.id());

        if (parsed.imageUrl() == null) {
            return generateWithoutReference(
                    currentUser, model, parsed.prompt(), request.size(), credentials);
        }

        ImageEditFile source = loadImage(parsed.imageUrl(), currentUser);
        ImageUrlResponse result = editAndStoreOne(
                currentUser,
                model,
                parsed.prompt(),
                source,
                null,
                normalizeSize(request.size()),
                credentials,
                false);
        return new RetouchResponse(parsed.prompt(), List.of(
                new RetouchImageResponse(result.url(), request.size(), FileAssetType.EDIT_RESULT)));
    }

    private ImageUrlResponse editUrl(CurrentUser currentUser, String imageUrl, String prompt) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new InvalidRequestException("图片地址不能为空");
        }
        ModelUseResolver.Resolved model = resolveDefaultModel(currentUser.siteId());
        ProviderCredentials credentials = imageCredentials(currentUser, model.id());
        ImageEditFile source = loadImage(imageUrl.strip(), currentUser);
        return editAndStoreOne(
                currentUser, model, prompt,
                source, null, null, credentials, false);
    }

    private ModelUseResolver.Resolved resolveDefaultModel(Long siteId) {
        return modelResolver.resolveForSite(siteId, null, LlmModelEntity.TYPE_IMAGE);
    }

    /**
     * Credentials every image endpoint in this service must use.
     *
     * <p>Image models may live on a differently scoped upstream key: when the New API relay is
     * disabled (local development) {@code resolve()} yields {@code app.llm.api-key}, and that chat
     * key is rejected by the gateway for image models with
     * {@code 404 model_not_found: "... is not available for this group"}. Routing every path
     * through {@link UserRelayCredentialResolver#resolveImage(CurrentUser)} keeps the distinction in
     * one place; with the relay enabled both methods are equivalent.</p>
     */
    private ProviderCredentials imageCredentials(CurrentUser currentUser) {
        return relayCredentials.resolveImage(currentUser);
    }

    /**
     * 按模型所属分组取钥匙。
     *
     * <p>图片模型常常只落在独立分组（例如站点的「图片生成」分组），账号默认分组那把钥匙
     * 到了上游会得到「该分组下无可用渠道」。模型已解析出来时一律走这个重载。</p>
     */
    private ProviderCredentials imageCredentials(CurrentUser currentUser, Long modelId) {
        return relayCredentials.resolveImage(currentUser, modelId);
    }

    private ImageUrlResponse editAndStoreOne(CurrentUser currentUser,
                                             ModelUseResolver.Resolved model,
                                             String prompt,
                                             ImageEditFile source,
                                             ImageEditFile mask,
                                             String size,
                                             ProviderCredentials credentials,
                                             boolean compositeMaskResult) {
        // 修图每一次编辑都从这里打上游，「润色无参考图」则走 ImageGenerationService，各自只计一次。
        imageRateLimiter.checkAndRecord(currentUser, 1);
        MaskedImageCompositor.ImageDimensions canvasDimensions = null;
        if (mask != null) {
            canvasDimensions = maskedImageCompositor.validate(source.content(), mask.content());
        }
        MaskedImageCompositor.ImageDimensions expectedGeneratedSize = null;
        if (canvasDimensions != null && size != null) {
            expectedGeneratedSize = validateImageSize(size);
            if (expectedGeneratedSize.width() != canvasDimensions.width()
                    || expectedGeneratedSize.height() != canvasDimensions.height()) {
                throw new InvalidRequestException(
                        "尺寸需要与原图一致（%d×%d）"
                                .formatted(canvasDimensions.width(), canvasDimensions.height()));
            }
        }
        String effectivePrompt = mask == null ? prompt : prompt + MASK_BOUNDARY_INSTRUCTION;
        ImageGenerationResult result = imageModelFallbackService.invoke(
                        currentUser.siteId(),
                        model,
                        selected -> imageEditingClient.edit(new ImageEditingCommand(
                                selected.upstreamKey(), effectivePrompt, source, mask, size,
                                credentials)))
                .value();
        if (result.images().isEmpty()) {
            throw new LlmInvocationException("图片编辑失败：模型没有返回可用图片，请重试");
        }
        return new ImageUrlResponse(
                storeGenerated(
                        currentUser,
                        result.images().get(0),
                        0,
                        FileSource.SMART_EDIT,
                        source,
                        mask,
                        compositeMaskResult,
                        expectedGeneratedSize,
                        effectivePrompt,
                        model.requestKey()),
                FileAssetType.EDIT_RESULT);
    }

    private RetouchResponse generateWithoutReference(CurrentUser currentUser,
                                                     ModelUseResolver.Resolved model,
                                                     String prompt,
                                                     String size,
                                                     ProviderCredentials credentials) {
        ImageGenerationResponse generated = imageGenerationService.generateWithRelayCredentials(
                currentUser,
                new ImageGenerationRequest(
                        prompt,
                        model.requestKey(),
                        normalizeSize(size),
                        null,
                        1,
                        null,
                        "smart_edit"),
                // 按模型所属分组取钥匙：修图的文生图分支与生图页共用同一批图片模型。
                null);

        List<RetouchImageResponse> images = new ArrayList<>();
        for (GeneratedImageResponse image : generated.images()) {
            images.add(new RetouchImageResponse(image.url(), size, image.assetType()));
        }
        String content = generated.images().stream()
                .map(GeneratedImageResponse::revisedPrompt)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(prompt);
        return new RetouchResponse(content, images);
    }

    private String storeGenerated(CurrentUser currentUser, GeneratedImage image, int index,
                                  FileSource fileSource, String prompt, String model) {
        return storeGenerated(currentUser, image, index, fileSource, null, prompt, model);
    }

    private String storeGenerated(CurrentUser currentUser, GeneratedImage image, int index,
                                  FileSource fileSource, Long conversationId, String prompt,
                                  String model) {
        return storeGenerated(currentUser, image, index, fileSource, conversationId, null, null, false,
                null, prompt, model);
    }

    private String storeGenerated(CurrentUser currentUser,
                                  GeneratedImage image,
                                  int index,
                                  FileSource fileSource,
                                  ImageEditFile source,
                                  ImageEditFile mask,
                                  boolean compositeMaskResult,
                                  MaskedImageCompositor.ImageDimensions expectedGeneratedSize,
                                  String prompt,
                                  String model) {
        return storeGenerated(currentUser, image, index, fileSource, null, source, mask,
                compositeMaskResult, expectedGeneratedSize, prompt, model);
    }

    private String storeGenerated(CurrentUser currentUser,
                                  GeneratedImage image,
                                  int index,
                                  FileSource fileSource,
                                  Long conversationId,
                                  ImageEditFile source,
                                  ImageEditFile mask,
                                  boolean compositeMaskResult,
                                  MaskedImageCompositor.ImageDimensions expectedGeneratedSize,
                                  String prompt,
                                  String model) {
        byte[] bytes;
        String mimeHint = null;
        if (image.hasInlineData()) {
            bytes = decode(image.base64());
        } else if (image.hasUrl()) {
            DownloadedImage downloaded = imageUrlDownloader.download(image.url());
            bytes = downloaded.content();
            mimeHint = downloaded.mimeType();
        } else {
            throw new LlmInvocationException("图片编辑失败：模型没有返回可用图片，请重试");
        }

        if (!compositeMaskResult && mask != null && expectedGeneratedSize != null) {
            maskedImageCompositor.validateGeneratedDimensions(bytes, expectedGeneratedSize);
        }

        if (compositeMaskResult && mask != null) {
            if (source == null) {
                throw new InvalidRequestException("局部重绘需要提供原图");
            }
            bytes = maskedImageCompositor.composite(
                    source.content(), mask.content(), bytes, expectedGeneratedSize);
        }

        ImageFormat format = detectFormat(bytes, mimeHint);
        // 绘画工具库的产出单独起名：下载下来一排 smart-edit-1.png 时，用户分不清哪张是抠图
        String prefix = FileSource.PAINTING.equals(fileSource)
                ? "painting"
                : FileSource.IMAGE_GENERATION.equals(fileSource) ? "chat-edit" : "smart-edit";
        String name = "%s-%d.%s".formatted(prefix, index + 1, format.extension());
        UploadedFileResponse stored = fileService.storeGenerated(
                currentUser, bytes, format.extension(), format.mimeType(), name,
                FileAssetType.EDIT_RESULT, fileSource, conversationId, prompt, model);
        return stored.url();
    }

    private ImageEditFile loadImage(String imageUrl, CurrentUser currentUser) {
        Optional<Long> platformFileId = platformFileId(imageUrl);
        if (platformFileId.isPresent()) {
            DownloadedFile file = fileService.download(platformFileId.get(), currentUser);
            byte[] bytes;
            try (var input = file.resource().getInputStream()) {
                bytes = input.readAllBytes();
            } catch (IOException exception) {
                throw new LlmInvocationException("图片读取失败：平台图片无法读取，请重新上传", exception);
            }
            return validatedEditFile(bytes, file.file().getMimeType(), file.file().getOriginalName(), "image");
        }

        DownloadedImage downloaded = imageUrlDownloader.download(imageUrl);
        return validatedEditFile(downloaded.content(), downloaded.mimeType(), null, "image");
    }

    private ImageEditFile editFile(MultipartFile file, String field) {
        validateUpload(file);
        String mime = normalizeMime(file.getContentType());
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = field + extensionFor(mime);
        }
        try {
            return new ImageEditFile(file.getBytes(), fileName, mime);
        } catch (IOException exception) {
            throw new InvalidRequestException("无法读取上传的图片，请换一张再试");
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("文件内容为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new PayloadTooLargeException("图片不能超过 10MB");
        }

        String mime = normalizeMime(file.getContentType());
        if (!isAllowedMime(mime)) {
            throw new InvalidRequestException("不支持的图片格式：%s（仅支持 PNG / JPEG / WebP）".formatted(file.getContentType()));
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!isAllowedExtension(extension)) {
            throw new InvalidRequestException("不支持的图片扩展名：%s".formatted(extension));
        }
    }

    private ImageEditFile validatedEditFile(byte[] bytes, String mimeHint, String originalName, String field) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidRequestException(field + " must not be empty");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new PayloadTooLargeException("图片不能超过 10MB");
        }

        String mime = normalizeMime(mimeHint);
        if (!isAllowedMime(mime)) {
            ImageFormat detected = detectInputFormat(bytes);
            mime = detected == null ? null : detected.mimeType();
        }
        if (!isAllowedMime(mime)) {
            throw new InvalidRequestException("不支持的图片格式：%s（仅支持 PNG / JPEG / WebP）".formatted(mimeHint));
        }

        String fileName = originalName;
        if (fileName == null || fileName.isBlank()) {
            fileName = field + extensionFor(mime);
        }
        return new ImageEditFile(bytes, fileName, mime);
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

        ImageFormat detected = detectInputFormat(content);
        if (detected != null) {
            return detected;
        }

        String normalized = normalizeMime(mimeTypeHint);
        return switch (normalized) {
            case PNG_MIME -> new ImageFormat("png", PNG_MIME);
            case JPEG_MIME -> new ImageFormat("jpg", JPEG_MIME);
            case WEBP_MIME -> new ImageFormat("webp", WEBP_MIME);
            default -> throw new LlmInvocationException(
                    "图片生成失败：不支持的图片格式 " + normalized);
        };
    }

    private static ImageFormat detectInputFormat(byte[] content) {
        if (content == null) {
            return null;
        }
        if (startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) {
            return new ImageFormat("png", PNG_MIME);
        }
        if (startsWith(content, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return new ImageFormat("jpg", JPEG_MIME);
        }
        if (content.length >= 12
                && startsAt(content, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && startsAt(content, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return new ImageFormat("webp", WEBP_MIME);
        }
        return null;
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

    private static Optional<Long> platformFileId(String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        Matcher matcher = PLATFORM_FILE_URL.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String normalizeMime(String mime) {
        if (mime == null) {
            return "";
        }
        String normalized = mime.strip().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).strip();
        }
        return normalized.equals("image/jpg") ? JPEG_MIME : normalized;
    }

    private static boolean isAllowedMime(String mime) {
        return PNG_MIME.equals(mime) || JPEG_MIME.equals(mime) || WEBP_MIME.equals(mime);
    }

    private static boolean isAllowedExtension(String extension) {
        return "png".equals(extension) || "jpg".equals(extension)
                || "jpeg".equals(extension) || "webp".equals(extension);
    }

    private static String extensionFor(String mime) {
        return switch (mime) {
            case JPEG_MIME -> ".jpg";
            case WEBP_MIME -> ".webp";
            default -> ".png";
        };
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void validateSaveValue(String save) {
        if (save == null || save.isBlank()) {
            return;
        }
        if (!"canvas".equals(save) && !"crop".equals(save)) {
            throw new InvalidRequestException("save 只能是 canvas 或 crop");
        }
    }

    private PhotoModelResponse toPhotoModel(ModelSummaryResponse summary, String upstreamModel) {
        BigDecimal points = summary.consumePoints() == null ? BigDecimal.ZERO : summary.consumePoints();
        return new PhotoModelResponse(
                summary.modelKey(),
                summary.modelName(),
                upstreamModel == null || upstreamModel.isBlank() ? summary.modelKey() : upstreamModel,
                summary.company() == null ? "" : summary.company(),
                summary.iconUrl() == null ? "" : summary.iconUrl(),
                points,
                new PhotoDrawResponse(DEFAULT_SIZES, DEFAULT_DPI, 512, 4096, 4, 1),
                new PhotoModelFlagsResponse(true));
    }

    private static String normalizeSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        String normalized = size.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "1k" -> "1024x1024";
            case "2k" -> "2048x2048";
            case "4k" -> "4096x4096";
            default -> size.strip();
        };
    }

    private static MaskedImageCompositor.ImageDimensions validateImageSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        Matcher matcher = IMAGE_SIZE_PATTERN.matcher(size.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new InvalidRequestException("尺寸格式应为 宽x高，例如 1024x1024");
        }

        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width < MIN_IMAGE_SIDE || height < MIN_IMAGE_SIDE) {
            throw new InvalidRequestException(
                    "尺寸的每条边至少 %d 像素".formatted(MIN_IMAGE_SIDE));
        }
        if (width % IMAGE_SIZE_MULTIPLE != 0 || height % IMAGE_SIZE_MULTIPLE != 0) {
            throw new InvalidRequestException(
                    "尺寸的宽和高必须是 %d 的倍数".formatted(IMAGE_SIZE_MULTIPLE));
        }
        if (width > MAX_IMAGE_WIDTH || height > MAX_IMAGE_HEIGHT) {
            throw new InvalidRequestException(
                    "尺寸不能超过 %d×%d".formatted(MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT));
        }
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        if (longSide > shortSide * 3) {
            throw new InvalidRequestException("图片长宽比需要在 1:3 到 3:1 之间");
        }
        return new MaskedImageCompositor.ImageDimensions(width, height);
    }

    private static RetouchPrompt extractPrompt(JsonNode messages) {
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw new InvalidRequestException("对话消息必须是非空数组");
        }

        String latestImage = null;
        String latestUserText = "";
        for (JsonNode message : messages) {
            JsonNode content = message.path("content");
            String text = textOf(content);
            String image = imageOf(content);
            if (image != null && !image.isBlank()) {
                latestImage = image;
            }
            if ("user".equalsIgnoreCase(message.path("role").asText()) && !text.isBlank()) {
                latestUserText = text;
            }
        }

        String prompt = latestUserText.isBlank()
                ? "根据提供的图片完成修图。"
                : latestUserText.strip();
        return new RetouchPrompt(prompt, latestImage);
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String value = part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(value);
            }
        }
        return text.toString();
    }

    private static String imageOf(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                String url = imageUrlNode(part.path("image_url"));
                if (url != null) {
                    return url;
                }
            }
            return null;
        }
        return imageUrlNode(content.path("image_url"));
    }

    private static String imageUrlNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        String url = node.path("url").asText("");
        return url.isBlank() ? null : url;
    }

    private record RetouchPrompt(String prompt, String imageUrl) {
    }

    private record ImageFormat(String extension, String mimeType) {
    }
}
