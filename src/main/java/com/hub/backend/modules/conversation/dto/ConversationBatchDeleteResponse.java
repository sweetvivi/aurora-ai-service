package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outcome of a batch delete.
 *
 * @param requested how many distinct ids the client asked for
 * @param deleted   how many were actually removed
 * @param skipped   requested minus deleted: unknown ids, ids of another account, and ids that were
 *                  already deleted. The three cases are deliberately not distinguished, so the
 *                  response cannot be used to probe for the existence of somebody else's thread.
 */
@Schema(name = "ConversationBatchDeleteResult", description = "批量删除结果")
public record ConversationBatchDeleteResponse(
        @Schema(description = "请求删除的数量", example = "3") int requested,
        @Schema(description = "实际删除的数量", example = "2") int deleted,
        @Schema(description = "跳过的数量（不存在、不属于当前账号或已删除）", example = "1") int skipped
) {

    public static ConversationBatchDeleteResponse of(int requested, int deleted) {
        return new ConversationBatchDeleteResponse(requested, deleted, requested - deleted);
    }
}