package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST /api/v1/conversations/batch-delete}.
 *
 * @param ids conversation ids to delete, at most {@value #MAX_IDS} per call
 */
@Schema(name = "ConversationBatchDelete", description = "批量删除会话请求")
public record ConversationBatchDeleteRequest(
        @Schema(description = "要删除的会话 ID，最多 100 个",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "[1,2,3]")
        @NotEmpty(message = "ids must not be empty")
        @Size(max = MAX_IDS, message = "ids must contain at most 100 entries")
        List<Long> ids
) {

    /** Upper bound of one batch, so a single call cannot turn into an unbounded write. */
    public static final int MAX_IDS = 100;

    /** Duplicates would inflate the requested count without deleting anything twice. */
    public List<Long> distinctIds() {
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }
}