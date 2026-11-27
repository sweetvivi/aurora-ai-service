package com.hub.backend.modules.smartedit.dto;

import com.hub.backend.modules.file.entity.FileAssetType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Single-image result used by the smart-editing image tools.
 */
@Schema(name = "SmartEditImageUrl", description = "智能修图图片结果")
public record ImageUrlResponse(
        @Schema(description = "平台可访问的图片地址") String url,
        @Schema(description = "资源用途", example = "edit_result") FileAssetType assetType
) {
}
