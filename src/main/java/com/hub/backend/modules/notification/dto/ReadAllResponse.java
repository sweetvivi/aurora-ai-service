package com.hub.backend.modules.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "NotificationReadAllResult", description = "全部标记已读的结果")
public record ReadAllResponse(int markedRead) {
}
