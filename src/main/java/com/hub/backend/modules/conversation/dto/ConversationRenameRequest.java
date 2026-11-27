package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/conversations/{id}}.
 */
@Schema(name = "ConversationRenameRequest", description = "重命名会话")
public record ConversationRenameRequest(
        @Schema(description = "新标题", requiredMode = Schema.RequiredMode.REQUIRED, example = "产品对比")
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title
) {
}
