package com.hub.backend.modules.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One submission from the public feedback page.
 *
 * @param category FEATURE / BUG / BILLING / OTHER; an unknown value is rejected rather than
 *                 silently stored, so the column stays filterable
 * @param content  what the sender typed, 10..2000 characters after trimming
 * @param contact  optional reply address (email / QQ / WeChat), left empty most of the time
 * @param pageUrl  the page the sender was on, optional
 */
public record FeedbackRequest(
        @NotBlank(message = "category is required")
        @Size(max = 32, message = "category is invalid")
        String category,

        @NotBlank(message = "content is required")
        @Size(max = 2000, message = "content is too long")
        String content,

        @Size(max = 120, message = "contact is too long")
        String contact,

        @Size(max = 512, message = "pageUrl is too long")
        String pageUrl
) {
}
