package com.hub.backend.modules.image.dto;

import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One generated image.
 *
 * @param fileId        identifier when the image is stored by this platform, otherwise {@code null}
 * @param url           where to fetch the image: a platform {@code /content} link when
 *                      {@code owned}, otherwise whatever URL the gateway returned
 * @param owned         {@code true} when the bytes live in this platform's storage and the image is
 *                      subject to the same ownership checks as an upload
 * @param assetType     semantic use of the asset: {@code generated} for text-to-image results
 * @param mimeType      media type, {@code null} for a gateway hosted image
 * @param sizeBytes     stored size, {@code null} for a gateway hosted image
 * @param revisedPrompt prompt the gateway actually used, when it reports one
 */
@Schema(name = "GeneratedImage", description = "生成的单张图片")
public record GeneratedImageResponse(
        @Schema(description = "平台附件 ID；上游直链时为 null", example = "12") Long fileId,
        @Schema(description = "图片地址") String url,
        @Schema(description = "图片是否由本平台存储（true 时 url 指向 /api/v1/files/{id}/content）",
                example = "true") boolean owned,
        @Schema(description = "资源用途", example = "generated") FileAssetType assetType,
        @Schema(description = "媒体类型", example = "image/png") String mimeType,
        @Schema(description = "字节数", example = "245871") Long sizeBytes,
        @Schema(description = "上游实际使用的提示词，可能为 null") String revisedPrompt
) {

    public static GeneratedImageResponse stored(UploadedFileResponse file, String revisedPrompt) {
        return new GeneratedImageResponse(
                file.id(), file.url(), true, file.assetType(), file.mimeType(),
                file.sizeBytes(), revisedPrompt);
    }

    public static GeneratedImageResponse hosted(String url, String revisedPrompt) {
        return new GeneratedImageResponse(
                null, url, false, FileAssetType.GENERATED, null, null, revisedPrompt);
    }
}
