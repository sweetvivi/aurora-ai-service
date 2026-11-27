package com.hub.backend.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "UserNotification", description = "当前用户可见的站内通知")
public record UserNotificationResponse(
        Long id,
        String title,
        String content,
        String priority,
        LocalDateTime publishedAt,
        boolean read
) {
}
