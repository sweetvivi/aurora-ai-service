package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * Body of {@code POST /api/admin/v1/models/batch-delete}.
 *
 * <p>{@code POST} rather than {@code DELETE} because a body on {@code DELETE} gets dropped by some
 * gateways and clients; {@code model-fallbacks/{id}/members/batch-remove} uses the same shape.</p>
 *
 * <p>Semantics are <b>partial success</b>: a model that is still available upstream is reported in
 * {@code skipped} with a reason instead of failing the whole call. Purging is cleanup work, so one
 * model that upstream restored a minute ago must not block the other ninety-nine.</p>
 */
@Schema(name = "AdminModelPurgeRequest",
        description = "批量清理已下线模型；仍在上游提供的模型不会被删除，计入 skipped")
public record AdminModelPurgeRequest(
        @Schema(description = "要删除的模型 ID，最多 100 个",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "[31,32]")
        @NotEmpty(message = "modelIds must not be empty")
        @Size(max = AdminModelBatchUpdateRequest.MAX_BATCH_ITEMS,
                message = "modelIds must contain at most 100 entries")
        List<Long> modelIds
) {

    /** Order-preserving de-duplication; the response counts are derived from it. */
    public List<Long> distinctModelIds() {
        return modelIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
