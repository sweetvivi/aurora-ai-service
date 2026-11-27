package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One question about the current document.
 *
 * @param message  the new question
 * @param history  previous turns, oldest first; the page keeps at most a handful
 * @param modelKey public model key chosen in the page's model switcher; blank means the site
 *                 default. A key that has since been retired falls back to the default instead of
 *                 failing the question, because the picker may be older than the catalogue.
 */
@Schema(name = "DocumentChatRequest", description = "基于当前文档提问")
public record DocumentChatRequest(
        @Schema(description = "问题", example = "这份文档的核心结论是什么？") String message,
        @Schema(description = "历史轮次，老的在前") List<DocumentChatTurn> history,
        @Schema(description = "对话模型 key，留空用站点默认模型", example = "deepseek-v4.1-flash")
        String modelKey
) {

    public DocumentChatRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
