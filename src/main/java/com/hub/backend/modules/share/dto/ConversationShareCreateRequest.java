package com.hub.backend.modules.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(name = "ConversationShareCreate", description = "创建会话分享请求")
public record ConversationShareCreateRequest(
        @Schema(description = "过期秒数；不传则链接长期有效", example = "604800")
        @Min(value = 60, message = "expiresInSeconds must be at least 60")
        @Max(value = 31536000, message = "expiresInSeconds must be at most 31536000")
        Long expiresInSeconds
) {
}
