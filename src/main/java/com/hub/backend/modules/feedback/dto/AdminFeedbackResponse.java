package com.hub.backend.modules.feedback.dto;

import com.hub.backend.modules.feedback.entity.UserFeedbackEntity;

import java.time.LocalDateTime;

/**
 * One feedback row as the admin list shows it.
 *
 * @param userId   null for anonymous submissions
 * @param username resolved at read time; empty when the sender was a guest or the account is gone
 */
public record AdminFeedbackResponse(
        Long id,
        Long userId,
        String username,
        String category,
        String content,
        String contact,
        String pageUrl,
        String source,
        Long agentConversationId,
        String transcriptSnapshot,
        String status,
        LocalDateTime createdAt) {

    public static AdminFeedbackResponse from(UserFeedbackEntity feedback, String username) {
        return new AdminFeedbackResponse(
                feedback.getId(),
                feedback.getUserId(),
                username == null ? "" : username,
                feedback.getCategory(),
                feedback.getContent(),
                feedback.getContact() == null ? "" : feedback.getContact(),
                feedback.getPageUrl() == null ? "" : feedback.getPageUrl(),
                feedback.getSource() == null ? "FEEDBACK" : feedback.getSource(),
                feedback.getAgentConversationId(),
                feedback.getTranscriptSnapshot() == null ? "" : feedback.getTranscriptSnapshot(),
                feedback.getStatus(),
                feedback.getCreatedAt());
    }
}
