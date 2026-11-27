package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of the two batch member endpoints.
 *
 * <p>Partial success is the point: {@code skipped} lists the members that were not touched and why,
 * while the rest of the batch still landed. {@code group} is the full refreshed snapshot so the
 * console can re-render without a second request.</p>
 *
 * @param requested size of the submitted list, before de-duplication
 * @param applied   members whose state actually changed
 * @param unchanged members already in the target state; counted as success
 * @param skipped   members not touched, with a human-readable reason
 */
@Schema(name = "AdminModelFallbackBatchMemberResult", description = "批量成员操作结果")
public record AdminModelFallbackBatchMemberResponse(
        @Schema(description = "操作后的整组快照") AdminModelFallbackGroupResponse group,
        @Schema(description = "提交的条数（去重前）", example = "3") int requested,
        @Schema(description = "状态真正发生变化的条数", example = "1") int applied,
        @Schema(description = "本来就是目标状态、算成功的条数", example = "1") int unchanged,
        @Schema(description = "未执行且未报错的成员及原因") List<Skipped> skipped
) {

    /** A member the batch deliberately left alone, with the reason shown to the operator. */
    @Schema(name = "SkippedFallbackMember", description = "未执行的成员及原因")
    public record Skipped(
            @Schema(description = "模型 ID", example = "25") Long modelId,
            @Schema(description = "原因，可直接展示",
                    example = "upstream is unavailable") String reason
    ) {
    }
}
