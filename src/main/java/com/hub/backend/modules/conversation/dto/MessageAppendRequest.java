package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/conversations/{id}/messages}.
 *
 * @param content message text, stored verbatim
 */
@Schema(name = "MessageAppend", description = "追加消息请求")
public record MessageAppendRequest(
        @Schema(description = "消息正文", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "content must not be blank")
        @Size(max = 32000, message = "content must be at most 32000 characters")
        String content
) {
}