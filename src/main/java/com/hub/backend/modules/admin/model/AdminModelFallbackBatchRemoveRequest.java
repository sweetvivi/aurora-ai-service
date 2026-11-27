package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * Body of {@code POST /api/admin/v1/model-fallbacks/{id}/members/batch-remove}.
 *
 * <p>{@code POST} rather than {@code DELETE} because a body on {@code DELETE} gets dropped by some
 * gateways and clients; {@code conversations/batch-delete} uses the same shape.</p>
 */
@Schema(name = "AdminModelFallbackBatchRemoveRequest",
        description = "批量移除版本组成员；已不是成员的 ID 计入 skipped")
public record AdminModelFallbackBatchRemoveRequest(
        @Schema(description = "要移除的成员模型 ID，最多 100 个",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "[23,24]")
        @NotEmpty(message = "modelIds must not be empty")
        @Size(max = MAX_MODEL_IDS, message = "modelIds must contain at most 100 entries")
        List<Long> modelIds
) {

    /** Upper bound of one batch, so a single call cannot turn into an unbounded write. */
    public static final int MAX_MODEL_IDS = 100;

    /** Order-preserving de-duplication; see {@code distinctModelIds()} on the enabled request. */
    public List<Long> distinctModelIds() {
        return modelIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
