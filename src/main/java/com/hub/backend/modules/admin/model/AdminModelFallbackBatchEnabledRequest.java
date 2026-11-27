package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Objects;

/**
 * Body of {@code PUT /api/admin/v1/model-fallbacks/{id}/members/batch-enabled}.
 *
 * @param modelIds {@code llm_models.id} of the members to switch; at most {@value #MAX_MODEL_IDS}
 * @param enabled  target state; {@code true} re-validates each member can still take part
 */
@Schema(name = "AdminModelFallbackBatchEnabledRequest",
        description = "批量启用或暂停版本组成员")
public record AdminModelFallbackBatchEnabledRequest(
        @Schema(description = "成员模型 ID，最多 100 个", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "[23,24,25]")
        @NotEmpty(message = "modelIds must not be empty")
        @Size(max = MAX_MODEL_IDS, message = "modelIds must contain at most 100 entries")
        List<Long> modelIds,
        @Schema(description = "true 启用，false 暂停", example = "false")
        @NotNull(message = "enabled must not be null") Boolean enabled
) {

    /** Upper bound of one batch, so a single call cannot turn into an unbounded write. */
    public static final int MAX_MODEL_IDS = 100;

    /**
     * Order-preserving de-duplication. Insertion order matters to callers that read the response
     * back in the same order they submitted, so this is a {@code distinct()} on a stream rather
     * than a {@code HashSet}.
     */
    public List<Long> distinctModelIds() {
        return modelIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
