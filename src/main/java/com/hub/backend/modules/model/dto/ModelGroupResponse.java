package com.hub.backend.modules.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A catalogue group, as returned inside {@link ModelCatalogResponse}.
 *
 * @param groupKey   stable key of the public model-version group
 * @param label      model-version display name shown to end users
 * @param company    vendor of the representative fallback member, may be {@code null}
 * @param modelCount number of visible catalogue entries in the group
 */
@Schema(name = "ModelGroup", description = "同版本模型组")
public record ModelGroupResponse(
        @Schema(description = "同版本模型组 key", example = "gpt-image-2.0") String groupKey,
        @Schema(description = "同版本模型组展示名称", example = "GPT-Image-2.0") String label,
        @Schema(description = "代表成员厂商，未设置为 null", example = "OpenAI") String company,
        @Schema(description = "该组在前台可见的目录条目数量", example = "1") int modelCount
) {
}
