package com.hub.backend.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NotificationUnreadCount", description = "当前用户未读通知数")
public record UnreadCountResponse(long unreadCount) {
}
