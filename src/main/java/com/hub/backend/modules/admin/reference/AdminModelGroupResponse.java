package com.hub.backend.modules.admin.reference;

import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One channel group, as a form option.
 *
 * <p>A group is the console's own filing dimension ({@code llm_model_groups}), separate from the
 * vendor-derived "family" the public catalogue computes.</p>
 */
@Schema(name = "AdminModelGroup", description = "模型分组条目（后台表单下拉用）")
public record AdminModelGroupResponse(
        @Schema(description = "分组 ID，写入模型时作为 groupId 提交", example = "1") Long id,
        @Schema(description = "所属站点 ID，null 表示全局") Long siteId,
        @Schema(description = "分组 key", example = "demo-relay") String groupKey,
        @Schema(description = "展示名称", example = "示例中转") String label,
        @Schema(description = "所属公司，可为 null") String company,
        @Schema(description = "状态", example = "ACTIVE") String status,
        @Schema(description = "排序号") int sortOrder
) {

    public static AdminModelGroupResponse of(LlmModelGroupEntity group) {
        return new AdminModelGroupResponse(
                group.getId(),
                group.getSiteId(),
                group.getGroupKey(),
                group.getLabel(),
                group.getCompany(),
                group.getStatus(),
                group.getSortOrder());
    }
}
