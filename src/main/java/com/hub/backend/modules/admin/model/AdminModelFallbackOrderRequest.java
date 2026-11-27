package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(name = "AdminModelFallbackOrderRequest",
        description = "按数组顺序设置版本组的降级顺序；必须包含当前全部成员")
public record AdminModelFallbackOrderRequest(
        @Schema(description = "按优先级排列的 llm_models.id")
        @NotEmpty(message = "modelIds must not be empty")
        List<Long> modelIds
) {
}
