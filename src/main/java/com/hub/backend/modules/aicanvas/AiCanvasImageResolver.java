package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.ImageUrlDownloader;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads user-owned or external image bytes for editing and multimodal prompts.
 */
@Component
public class AiCanvasImageResolver {

    /**
     * 尾部允许查询串 / 片段：前端给媒体地址追加过 `?t=<时间戳>` 之类的缓存戳，
     * 只按裸路径匹配会把平台自己的地址当成外部直链去下载，最终报 502。
     */
    private static final Pattern PLATFORM_FILE_URL =
            Pattern.compile("(?:^|/)api/v1/files/(\\d+)/content/?(?:[?#].*)?$");
    private static final Pattern CANVAS_ASSET_URL =
            Pattern.compile("(?:^|/)api/v1/ai-canvas/assets/([^/?#]+)/content/?(?:[?#].*)?$");
    private static final Pattern DATA_URL =
            Pattern.compile("^data:([^;,]+);base64,(.+)$", Pattern.DOTALL);

    private final FileService fileService;
    private final AiCanvasAssetService assetService;
    private final ImageUrlDownloader imageUrlDownloader;

    public AiCanvasImageResolver(FileService fileService,
                                 AiCanvasAssetService assetService,
                                 ImageUrlDownloader imageUrlDownloader) {
        this.fileService = fileService;
        this.assetService = assetService;
        this.imageUrlDownloader = imageUrlDownloader;
    }

    public LoadedImage fromMultipart(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("参考图内容为空");
        }
        try {
            return new LoadedImage(file.getBytes(), normalizeImageMime(file.getContentType()), extensionOf(file));
        } catch (IOException exception) {
            throw new InvalidRequestException("无法读取参考图，请换一张再试");
        }
    }

    public LoadedImage load(String imageUrl, CurrentUser currentUser) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new InvalidRequestException("图片地址不能为空");
        }
        Optional<Long> platformFileId = platformFileId(imageUrl);
        if (platformFileId.isPresent()) {
            DownloadedFile file = fileService.download(platformFileId.get(), currentUser);
            try (var input = file.resource().getInputStream()) {
                return new LoadedImage(
                        input.readAllBytes(),
                        normalizeImageMime(file.file().getMimeType()),
                        file.file().getExtension());
            } catch (IOException exception) {
                throw new LlmInvocationException("图片读取失败：平台图片无法读取，请重新上传", exception);
            }
        }
        Optional<String> canvasAssetKey = canvasAssetKey(imageUrl);
        if (canvasAssetKey.isPresent()) {
            AiCanvasAssetService.DownloadedAsset asset =
                    assetService.download(currentUser, canvasAssetKey.get());
            if (!asset.asset().getType().equals("image")) {
                throw new InvalidRequestException("画布素材必须是图片");
            }
            try (var input = asset.resource().getInputStream()) {
                return new LoadedImage(
                        input.readAllBytes(),
                        normalizeImageMime(asset.asset().getMimeType()),
                        asset.asset().getExtension());
            } catch (IOException exception) {
                throw new LlmInvocationException("图片读取失败：画布图片无法读取，请重新上传", exception);
            }
        }

        DownloadedImage downloaded = imageUrlDownloader.download(imageUrl.strip());
        return new LoadedImage(
                downloaded.content(),
                normalizeImageMime(downloaded.mimeType()),
                extensionForMime(normalizeImageMime(downloaded.mimeType())));
    }

    public LoadedImage decode(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("图片数据不能为空");
        }
        Matcher matcher = DATA_URL.matcher(value.strip());
        String mimeType = "image/png";
        String encoded = value.strip();
        if (matcher.matches()) {
            mimeType = normalizeImageMime(matcher.group(1));
            encoded = matcher.group(2);
        }
        try {
            byte[] content = Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
            if (content.length == 0) {
                throw new InvalidRequestException("图片数据为空");
            }
            return new LoadedImage(content, mimeType, extensionForMime(mimeType));
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("图片数据无法识别");
        }
    }

    /**
     * 从平台附件地址里取出附件 ID（画布视频把节点产物当首帧时用它直接复用附件）。
     */
    static Optional<Long> platformFileId(String url) {
        Matcher matcher = PLATFORM_FILE_URL.matcher(url.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> canvasAssetKey(String url) {
        Matcher matcher = CANVAS_ASSET_URL.matcher(url.strip());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String normalizeImageMime(String mimeType) {
        String normalized = mimeType == null
                ? ""
                : mimeType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("image/")) {
            throw new InvalidRequestException("参考文件必须是图片");
        }
        return normalized;
    }

    private static String extensionOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.contains(".")) {
            return extensionForMime(normalizeImageMime(file.getContentType()));
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String extensionForMime(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }

    public record LoadedImage(byte[] content, String mimeType, String extension) {
        public LoadedImage {
            content = content == null ? new byte[0] : content.clone();
        }
    }
}
