package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request for exporting a processing result back into the source file's own format.
 *
 * @param text   the AI result to write into the file; required
 * @param suffix optional file name suffix ({@code 翻译} / {@code 润色} …), so the download does not
 *               overwrite the original
 */
@Schema(name = "DocumentExportRequest", description = "按原格式保存处理结果")
public record DocumentExportRequest(
        @Schema(description = "处理结果正文", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "一、项目背景……")
        String text,

        @Schema(description = "文件名后缀，如「翻译」→ 项目方案书-翻译.pdf", example = "翻译")
        String suffix
) {
}
