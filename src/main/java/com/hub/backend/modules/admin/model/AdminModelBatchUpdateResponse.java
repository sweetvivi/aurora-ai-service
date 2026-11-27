package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of {@code PUT /api/admin/v1/models/batch}.
 *
 * <p>{@code models} carries the full updated rows so the console can merge them in place instead of
 * reloading the whole catalogue.</p>
 */
@Schema(name = "AdminModelBatchUpdateResult", description = "批量修改结果")
public record AdminModelBatchUpdateResponse(
        @Schema(description = "去重后请求修改的条数", example = "2") int requested,
        @Schema(description = "实际写入的条数；全有或全无，等于 requested", example = "2") int updated,
        @Schema(description = "更新后的完整模型行，可直接合并进列表") List<AdminModelResponse> models
) {
}
