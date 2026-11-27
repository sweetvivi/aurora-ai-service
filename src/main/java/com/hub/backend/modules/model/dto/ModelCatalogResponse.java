package com.hub.backend.modules.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The whole visible catalogue in one response.
 *
 * <p>Each entry represents a public model-version group rather than a concrete upstream model.
 * Concrete fallback members and upstream routes remain internal.</p>
 *
 * @param groups      visible model-version groups in display order
 * @param models      visible model-version entries in display order
 * @param totalModels number of model-version entries in {@code models}
 */
@Schema(name = "ModelCatalog", description = "同版本模型组目录")
public record ModelCatalogResponse(
        @Schema(description = "同版本模型组列表，按展示顺序") List<ModelGroupResponse> groups,
        @Schema(description = "同版本模型组目录条目，按展示顺序") List<ModelSummaryResponse> models,
        @Schema(description = "同版本模型组数量", example = "4") int totalModels
) {
}
