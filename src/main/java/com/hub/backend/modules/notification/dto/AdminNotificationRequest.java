package com.hub.backend.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "AdminNotificationRequest", description = "新增或编辑站内通知")
public record AdminNotificationRequest(
        @Schema(description = "通知标题", example = "服务升级通知")
        @NotBlank(message = "must not be blank")
        @Size(max = 120, message = "must be at most 120 characters")
        String title,

        @Schema(description = "通知正文", example = "系统将于今晚 23:00 进行维护。")
        @NotBlank(message = "must not be blank")
        @Size(max = 10000, message = "must be at most 10000 characters")
        String content,

        @Schema(description = "通知级别：NORMAL / HIGH；省略时为 NORMAL", example = "NORMAL")
        String priority,

        @Schema(description = "发布状态：DRAFT / PUBLISHED", example = "PUBLISHED")
        String status,

        @Schema(description = "目标站点；null 表示全部站点", example = "1")
        Long siteId
) {
}
