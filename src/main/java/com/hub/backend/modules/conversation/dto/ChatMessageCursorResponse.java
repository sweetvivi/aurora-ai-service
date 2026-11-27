package com.hub.backend.modules.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of conversation messages.
 *
 * <p>Opening a thread returns the <em>latest</em> page (newest messages). Pass {@code prevCursor}
 * back as {@code before} to load older turns; pass {@code nextCursor} as {@code cursor} only when
 * the client needs turns newer than what it already has.</p>
 *
 * @param items      messages in ascending order of {@code sequenceNo}
 * @param nextCursor pass as {@code cursor} to fetch newer messages; {@code null} at the end
 * @param hasMore    whether a newer page exists
 * @param prevCursor pass as {@code before} to fetch older messages; {@code null} at the start
 * @param hasOlder   whether an older page exists
 */
@Schema(name = "ChatMessagePage", description = "消息分页响应（游标）")
public record ChatMessageCursorResponse(
        @Schema(description = "本页消息，按序号升序") List<ChatMessageResponse> items,
        @Schema(description = "更新消息游标，为 null 表示已到末尾", example = "20") Long nextCursor,
        @Schema(description = "是否还有更新的消息", example = "false") boolean hasMore,
        @Schema(description = "更早消息游标，为 null 表示已到开头", example = "1") Long prevCursor,
        @Schema(description = "是否还有更早的消息", example = "true") boolean hasOlder
) {

    public static ChatMessageCursorResponse of(List<ChatMessageResponse> items,
                                              Long nextCursor,
                                              boolean hasMore,
                                              Long prevCursor,
                                              boolean hasOlder) {
        return new ChatMessageCursorResponse(
                List.copyOf(items), nextCursor, hasMore, prevCursor, hasOlder);
    }

    /** @deprecated prefer {@link #of}; kept for older call sites that only walked forward */
    public static ChatMessageCursorResponse terminal(List<ChatMessageResponse> items) {
        Long prev = items.isEmpty() ? null : items.get(0).id();
        return of(items, null, false, prev, false);
    }

    /** @deprecated prefer {@link #of}; kept for older call sites that only walked forward */
    public static ChatMessageCursorResponse more(List<ChatMessageResponse> items, Long nextCursor) {
        Long prev = items.isEmpty() ? null : items.get(0).id();
        return of(items, nextCursor, true, prev, false);
    }
}
