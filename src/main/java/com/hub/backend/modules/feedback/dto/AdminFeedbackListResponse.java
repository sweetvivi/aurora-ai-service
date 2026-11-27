package com.hub.backend.modules.feedback.dto;

import java.util.List;

/**
 * @param total        rows matching the current filters (not the page size)
 * @param pendingCount rows still in {@code NEW}, independent of the filters so the
 *                     「待处理」badge stays meaningful while a status filter is applied
 */
public record AdminFeedbackListResponse(
        List<AdminFeedbackResponse> list,
        long total,
        long pendingCount) {
}
