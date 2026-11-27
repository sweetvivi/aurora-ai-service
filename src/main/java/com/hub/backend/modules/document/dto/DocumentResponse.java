package com.hub.backend.modules.document.dto;

import com.hub.backend.modules.document.entity.DocumentEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Metadata of one document in the processing workspace.
 *
 * <p>{@code preview} is a short excerpt so the page can show something before any tool runs;
 * the full text is only returned by an explicit {@code extract} action.</p>
 */
@Schema(name = "Document", description = "文档处理工作区里的文档")
public record DocumentResponse(
        @Schema(description = "文档 ID", example = "1") Long id,
        @Schema(description = "原始文件名", example = "项目方案书.pdf") String name,
        @Schema(description = "页面格式标签：PDF / Word / Excel / TXT", example = "PDF") String format,
        @Schema(description = "扩展名", example = "pdf") String extension,
        @Schema(description = "字节数", example = "2411724") long sizeBytes,
        @Schema(description = "页数，仅 PDF 有", example = "12") Integer pageCount,
        @Schema(description = "抽取到的字符数", example = "8420") int charCount,
        @Schema(description = "正文开头摘录") String preview,
        @Schema(description = "来源：UPLOAD / SAMPLE", example = "UPLOAD") String source,
        @Schema(description = "上传时间") LocalDateTime createdAt
) {

    private static final int PREVIEW_CHARS = 240;

    public static DocumentResponse from(DocumentEntity document) {
        return new DocumentResponse(
                document.getId(),
                document.getOriginalName(),
                document.getFormat(),
                document.getExtension(),
                document.getSizeBytes(),
                document.getPageCount(),
                document.getCharCount(),
                preview(document.getContent()),
                document.getSource(),
                document.getCreatedAt());
    }

    private static String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll(" {2,}", " ").strip();
        if (flat.codePointCount(0, flat.length()) <= PREVIEW_CHARS) {
            return flat;
        }
        int end = flat.offsetByCodePoints(0, PREVIEW_CHARS);
        return flat.substring(0, end) + "…";
    }
}
