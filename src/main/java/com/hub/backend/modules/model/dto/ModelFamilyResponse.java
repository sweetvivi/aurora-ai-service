package com.hub.backend.modules.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Models of one vendor, as shown by a model picker.
 *
 * @param familyKey    stable machine key ({@code brand_key} when configured, else company slug)
 * @param displayName  vendor name shown in the picker
 * @param iconUrl      brand icon when configured, otherwise {@code null}
 * @param description  brand short blurb when configured, otherwise {@code null}
 * @param sortOrder    brand sort order when configured; unconfigured families use a large value
 * @param configured   {@code true} when an ACTIVE {@code llm_model_brands} row backs this family
 * @param modelCount   number of models in this family
 * @param models       the models themselves, in catalogue order
 */
@Schema(name = "ModelFamily", description = "模型家族（按厂商归组）")
public record ModelFamilyResponse(
        @Schema(description = "家族 key，配置品牌时等于 brand_key", example = "deepseek") String familyKey,
        @Schema(description = "厂商名称", example = "DeepSeek") String displayName,
        @Schema(description = "厂商图标，未配置为 null") String iconUrl,
        @Schema(description = "厂商短说明，未配置为 null") String description,
        @Schema(description = "排序号（配置品牌优先）", example = "10") int sortOrder,
        @Schema(description = "是否后台已配置品牌", example = "true") boolean configured,
        @Schema(description = "该家族下的模型数量", example = "4") int modelCount,
        @Schema(description = "模型列表，按目录顺序") List<ModelSummaryResponse> models
) {
}
