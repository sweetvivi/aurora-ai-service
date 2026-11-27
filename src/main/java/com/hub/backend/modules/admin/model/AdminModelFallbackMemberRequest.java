package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "AdminModelFallbackMemberRequest",
        description = "向版本组加入一个具体模型")
public record AdminModelFallbackMemberRequest(
        @Schema(description = "llm_models.id", example = "42")
        @NotNull(message = "modelId must not be null")
        Long modelId,

        @Schema(description = "加入后是否立即启用；默认 true")
        Boolean enabled
) {
}
