package com.hub.backend.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AgentChatMessagePage", description = "智能体会话消息分页")
public record AgentChatMessageCursorResponse(
        List<AgentChatMessageResponse> items,
        Long nextCursor,
        boolean hasMore
) {

    public static AgentChatMessageCursorResponse terminal(List<AgentChatMessageResponse> items) {
        return new AgentChatMessageCursorResponse(List.copyOf(items), null, false);
    }

    public static AgentChatMessageCursorResponse more(List<AgentChatMessageResponse> items, Long nextCursor) {
        return new AgentChatMessageCursorResponse(List.copyOf(items), nextCursor, true);
    }
}
