package com.hub.backend.modules.document.dto;

import com.hub.backend.modules.document.entity.DocumentHistoryEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One history entry with the result body, used when the page re-opens a past result.
 *
 * <p>{@code fileId} points at a stored blob for the file-producing tools (merge, split, encrypt,
 * watermark, images, text → PDF). It is {@code null} both for text results and for files the user
 * has since deleted from storage — the page then simply has nothing to download.</p>
 */
@Schema(name = "DocumentHistoryDetail", description = "处理历史详情（含结果正文）")
public record DocumentHistoryDetail(
        Long id,
        Long documentId,
        String documentName,
        String documentFormat,
        String action,
        String label,
        String status,
        String errorMessage,
        String text,
        Long fileId,
        String fileName,
        String fileFormat,
        boolean truncated,
        Integer durationMs,
        LocalDateTime createdAt
) {

    public static DocumentHistoryDetail of(DocumentHistoryEntity entity) {
        return new DocumentHistoryDetail(
                entity.getId(),
                entity.getDocumentId(),
                entity.getDocumentName(),
                entity.getDocumentFormat(),
                entity.getAction(),
                entity.getLabel(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getResultText(),
                entity.getFileId(),
                entity.getFileName(),
                entity.getFileFormat(),
                entity.isTruncated(),
                entity.getDurationMs(),
                entity.getCreatedAt());
    }
}
