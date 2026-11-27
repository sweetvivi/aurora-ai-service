package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request for a built-in sample document.
 *
 * @param format page format label ({@code PDF} / {@code Word} / {@code Excel} / {@code TXT}),
 *               or the file extension
 */
@Schema(name = "DocumentSampleRequest", description = "创建一份示例文档")
public record DocumentSampleRequest(
        @Schema(description = "示例类型", example = "PDF") String format
) {
}
