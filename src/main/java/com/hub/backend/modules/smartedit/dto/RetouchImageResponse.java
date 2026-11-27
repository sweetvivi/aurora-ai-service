package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hub.backend.modules.file.entity.FileAssetType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One image returned by {@code /retouch}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "SmartEditRetouchImage", description = "智能修图出图结果")
public record RetouchImageResponse(
        String url,
        String size,
        @Schema(description = "资源用途：generated / edit_result") FileAssetType assetType
) {
}
