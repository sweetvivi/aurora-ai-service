package com.hub.backend.modules.share.dto;

import com.hub.backend.modules.conversation.dto.ChatMessageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "SharedConversation", description = "公开会话分享快照")
public record SharedConversationResponse(
        @Schema(description = "源会话 ID") Long conversationId,
        @Schema(description = "快照标题") String title,
        @Schema(description = "快照消息数") int messageCount,
        @Schema(description = "源会话创建时间") LocalDateTime createdAt,
        @Schema(description = "可展示的消息，按会话内序号升序") List<ChatMessageResponse> messages
) {
}
