package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A turn of the document conversation, as kept by the page.
 *
 * @param role    {@code user} or {@code assistant}
 * @param content message text
 */
@Schema(name = "DocumentChatTurn", description = "文档对话的一轮消息")
public record DocumentChatTurn(String role, String content) {

    public boolean isUser() {
        return "user".equalsIgnoreCase(role);
    }
}
