package com.hub.backend.modules.smartedit.dto;

import com.hub.backend.modules.file.entity.FileAssetType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Upload response consumed by the smart-editing frontend.
 */
@Schema(name = "SmartEditUploadedAsset", description = "智能修图上传结果")
public record UploadedAssetResponse(
        @Schema(description = "可访问的图片地址") String url,
        @Schema(description = "平台附件 ID") String object,
        @Schema(description = "资源用途", example = "reference") FileAssetType assetType
) {
}
