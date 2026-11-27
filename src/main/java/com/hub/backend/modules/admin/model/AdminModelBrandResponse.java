package com.hub.backend.modules.admin.model;

import com.hub.backend.modules.model.entity.LlmModelBrandEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "AdminModelBrand", description = "模型厂商")
public record AdminModelBrandResponse(
        @Schema(description = "ID") Long id,
        @Schema(description = "厂商 key", example = "kling") String brandKey,
        @Schema(description = "展示名称", example = "可灵") String displayName,
        @Schema(description = "图标地址") String iconUrl,
        @Schema(description = "短说明") String description,
        @Schema(description = "排序号") int sortOrder,
        @Schema(description = "状态") String status,
        @Schema(description = "company 匹配到该厂商的模型数量") int linkedModelCount,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt
) {
    public static AdminModelBrandResponse of(LlmModelBrandEntity brand, int linkedModelCount) {
        return new AdminModelBrandResponse(
                brand.getId(),
                brand.getBrandKey(),
                brand.getDisplayName(),
                brand.getIconUrl(),
                brand.getDescription(),
                brand.getSortOrder(),
                brand.getStatus(),
                linkedModelCount,
                brand.getCreatedAt(),
                brand.getUpdatedAt());
    }
}
