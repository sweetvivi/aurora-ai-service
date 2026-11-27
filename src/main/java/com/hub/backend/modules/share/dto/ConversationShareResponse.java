package com.hub.backend.modules.share.dto;

import com.hub.backend.modules.share.entity.ConversationShareEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "ConversationShare", description = "会话分享链接")
public record ConversationShareResponse(
        @Schema(description = "分享记录 ID，用于撤销", example = "1") Long id,
        @Schema(description = "会话 ID", example = "1") Long conversationId,
        @Schema(description = "可在前端分享页使用的 URL", example = "/share/abc123") String url,
        @Schema(description = "分享 token，只展示一次", example = "abc123") String token,
        @Schema(description = "快照标题") String title,
        @Schema(description = "过期时间", example = "2026-10-20T12:00:00") LocalDateTime expiresAt,
        @Schema(description = "撤销时间", example = "2026-10-15T12:00:00") LocalDateTime revokedAt,
        @Schema(description = "创建时间") LocalDateTime createdAt
) {

    public static ConversationShareResponse from(ConversationShareEntity share, String token) {
        return new ConversationShareResponse(
                share.getId(),
                share.getConversationId(),
                "/api/v1/shares/" + token,
                token,
                share.getTitle(),
                share.getExpiresAt(),
                share.getRevokedAt(),
                share.getCreatedAt());
    }

    /**
     * Listing can only show metadata: the raw token is deliberately not persisted and is thus
     * impossible to recover after create().
     */
    public static ConversationShareResponse from(ConversationShareEntity share) {
        return new ConversationShareResponse(
                share.getId(),
                share.getConversationId(),
                null,
                null,
                share.getTitle(),
                share.getExpiresAt(),
                share.getRevokedAt(),
                share.getCreatedAt());
    }
}
