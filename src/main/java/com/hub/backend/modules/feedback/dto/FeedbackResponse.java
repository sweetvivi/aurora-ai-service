package com.hub.backend.modules.feedback.dto;

import java.time.LocalDateTime;

/**
 * @param id        the stored row, so the sender's report can be traced later
 * @param createdAt server-side receive time, shown back as "已收到"
 */
public record FeedbackResponse(
        long id,
        LocalDateTime createdAt
) {
}
