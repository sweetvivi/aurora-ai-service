package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(name = "AdminModelFallbackMemberEnabledRequest",
        description = "启用或暂停版本组中的一个成员")
public record AdminModelFallbackMemberEnabledRequest(
        @Schema(description = "是否参与降级", example = "false")
        @NotNull(message = "enabled must not be null")
        Boolean enabled
) {
}
