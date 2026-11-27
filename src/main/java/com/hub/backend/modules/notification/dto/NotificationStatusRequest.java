package com.hub.backend.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "NotificationStatusRequest", description = "发布或撤回站内通知")
public record NotificationStatusRequest(
        @Schema(description = "目标状态：DRAFT / PUBLISHED", example = "PUBLISHED")
        @NotBlank(message = "must not be blank")
        String status
) {
}
