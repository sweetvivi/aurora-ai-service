package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of {@code POST /api/admin/v1/models/batch-delete}.
 *
 * @param requested de-duplicated count of submitted ids
 * @param deleted   rows actually removed
 * @param skipped   models that were left alone, each with a reason the console can display as-is;
 *                  an id that no longer exists also lands here, which makes a retry idempotent
 */
@Schema(name = "AdminModelPurgeResult", description = "批量清理已下线模型的结果")
public record AdminModelPurgeResponse(
        int requested,
        int deleted,
        List<Skipped> skipped
) {

    @Schema(name = "AdminModelPurgeSkipped", description = "未删除的模型及原因")
    public record Skipped(Long modelId, String reason) {
    }
}
