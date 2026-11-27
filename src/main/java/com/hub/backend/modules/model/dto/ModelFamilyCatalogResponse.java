package com.hub.backend.modules.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Every visible model folded into families.
 *
 * @param families     families in catalogue order
 * @param totalFamilies number of families
 * @param totalModels   number of models across all families
 */
@Schema(name = "ModelFamilyCatalog", description = "模型家族目录")
public record ModelFamilyCatalogResponse(
        @Schema(description = "家族列表，按目录顺序") List<ModelFamilyResponse> families,
        @Schema(description = "家族数量", example = "1") int totalFamilies,
        @Schema(description = "模型总数", example = "4") int totalModels
) {
}