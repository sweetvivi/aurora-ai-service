package com.hub.backend.modules.conversation.dto;

import com.hub.backend.modules.conversation.entity.ConversationEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * A conversation as shown in the history list.
 */
@Schema(name = "ConversationSummary", description = "会话摘要")
public record ConversationSummaryResponse(
        @Schema(description = "会话 ID", example = "1") Long id,
        @Schema(description = "会话标题", example = "用一句话介绍你自己") String title,
        @Schema(description = "会话类型", example = "CHAT") String conversationType,
        @Schema(description = "创建时写入的目录模型主键，未指定时为 null", example = "1") Long modelId,
        @Schema(description = "创建时使用的模型 key；目录已删或未指定时为 null", example = "gpt-6-astra")
        String modelKey,
        @Schema(description = "创建时使用的模型展示名；无目录行时为 null", example = "GPT-6 Astra")
        String modelName,
        @Schema(description = "本会话偏好的生图模型 key；未设置时为 null", example = "gpt-image-2")
        String imageModelKey,
        @Schema(description = "本会话偏好的生图模型展示名；未设置时为 null", example = "GPT Image 2")
        String imageModelName,
        @Schema(description = "消息条数", example = "4") int messageCount,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间，历史列表按它倒序") LocalDateTime updatedAt
) {

    public static ConversationSummaryResponse from(ConversationEntity conversation) {
        return from(conversation, null, null, null, null);
    }

    public static ConversationSummaryResponse from(ConversationEntity conversation,
                                                   String modelKey,
                                                   String modelName) {
        return from(conversation, modelKey, modelName, null, null);
    }

    public static ConversationSummaryResponse from(ConversationEntity conversation,
                                                   String modelKey,
                                                   String modelName,
                                                   String imageModelKey,
                                                   String imageModelName) {
        return new ConversationSummaryResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getConversationType(),
                conversation.getModelId(),
                modelKey,
                modelName,
                imageModelKey,
                imageModelName,
                conversation.getMessageCount(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }
}
