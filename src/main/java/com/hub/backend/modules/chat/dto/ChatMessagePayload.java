package com.hub.backend.modules.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * One turn supplied by the caller.
 */
@Schema(name = "ChatMessagePayload", description = "单条上下文消息")
public record ChatMessagePayload(
        @Schema(description = "消息角色", allowableValues = {"system", "user", "assistant", "tool"},
                requiredMode = Schema.RequiredMode.REQUIRED, example = "user")
        @NotBlank(message = "role is required")
        @Pattern(regexp = "system|user|assistant|tool",
                message = "role must be one of system, user, assistant, tool")
        String role,

        @Schema(description = "消息正文；只发图片时可以是空串",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "你好")
        @NotNull(message = "content is required")
        @Size(max = 32000, message = "content must be at most 32000 characters")
        String content,

        @Schema(description = """
                随消息一起送进模型的附件 ID（来自 POST /api/v1/files）。
                只有 user 角色生效，最多 5 个：图片走多模态；
                txt/md/csv/json 与 pdf/docx/xlsx/pptx 会抽取文本后展开进正文。""",
                example = "[12,13]")
        @Size(max = 5, message = "at most 5 attachments per message")
        List<Long> attachmentIds
) {

    public ChatMessagePayload {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    /** 纯文本消息，兼容既有调用方 */
    public ChatMessagePayload(String role, String content) {
        this(role, content, List.of());
    }

    public boolean hasAttachments() {
        return !attachmentIds.isEmpty();
    }
}
