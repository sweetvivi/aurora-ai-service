package com.hub.backend.modules.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param status NEW / HANDLED, see {@code FeedbackService.updateStatus}
 */
public record FeedbackStatusRequest(
        @NotBlank(message = "status is required")
        @Size(max = 16, message = "status is invalid")
        String status
) {
}
