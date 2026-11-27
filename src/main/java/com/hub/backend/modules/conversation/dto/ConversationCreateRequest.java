package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/conversations}.
 *
 * @param title            optional name; when absent the title is derived from the first user message
 * @param conversationType optional kind of thread; defaults to {@code CHAT}. The drawing page creates
 *                         {@code PAINTING} threads so they stay out of the chat history.
 */
@Schema(name = "ConversationCreate", description = "创建会话请求")
public record ConversationCreateRequest(
        @Schema(description = "会话标题，缺省时由首条用户消息自动生成", example = "杭州旅行计划")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Schema(description = "会话类型：CHAT（模型对话，缺省）/ PAINTING（绘画作品）",
                example = "CHAT", allowableValues = {"CHAT", "PAINTING"})
        @Size(max = 32, message = "conversationType must be at most 32 characters")
        String conversationType
) {

    public String titleOrNull() {
        return title == null || title.isBlank() ? null : title.strip();
    }

    public String conversationTypeOrNull() {
        return conversationType == null || conversationType.isBlank() ? null : conversationType.strip();
    }
}
