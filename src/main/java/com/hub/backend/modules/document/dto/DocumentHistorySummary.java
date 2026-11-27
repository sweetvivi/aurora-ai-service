package com.hub.backend.modules.document.dto;

import com.hub.backend.modules.document.entity.DocumentHistoryEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One row in the history list. Deliberately without {@code text} — a single AI result can be tens
 * of thousands of characters, and a list of twenty of those would be megabytes for no reason.
 * The page fetches {@link DocumentHistoryDetail} only when a row is opened.
 *
 * @param charCount number of characters the stored result has, for the "1,234 字" line
 */
@Schema(name = "DocumentHistorySummary", description = "处理历史列表项")
public record DocumentHistorySummary(
        Long id,
        Long documentId,
        String documentName,
        String documentFormat,
        String action,
        String label,
        @Schema(description = "SUCCESS / FAILED") String status,
        String errorMessage,
        String fileFormat,
        boolean truncated,
        int charCount,
        Integer durationMs,
        LocalDateTime createdAt
) {

    public static DocumentHistorySummary of(DocumentHistoryEntity entity) {
        return new DocumentHistorySummary(
                entity.getId(),
                entity.getDocumentId(),
                entity.getDocumentName(),
                entity.getDocumentFormat(),
                entity.getAction(),
                entity.getLabel(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getFileFormat(),
                entity.isTruncated(),
                entity.getResultChars(),
                entity.getDurationMs(),
                entity.getCreatedAt());
    }
}
