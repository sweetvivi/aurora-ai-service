package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One row of "my drafts".
 */
@Schema(name = "ThesisDocumentSummary", description = "稿件列表条目")
public record ThesisDocumentSummaryResponse(
        @Schema(description = "稿件 ID", example = "1") Long id,
        @Schema(description = "论文题目") String topic,
        @Schema(description = "学历层次", example = "本科在读") String educationLevel,
        @Schema(description = "文本类型", example = "论文助手") String paperType,
        @Schema(description = "大纲结构", example = "3") int outlineDepth,
        @Schema(description = "学科 / 专业", example = "工学 / 计算机科学与技术") String disciplineMajor,
        @Schema(description = "目标字数", example = "8000") int targetWords,
        @Schema(description = "内容状态") String status,
        @Schema(description = "大纲版本号", example = "2") int outlineVersion,
        @Schema(description = "是否已保存过全文") boolean hasFullText,
        @Schema(description = "已保存正文的字数，0 表示还没有全文", example = "8120") int fullTextWordCount,
        @Schema(description = "全文生成时间；为空表示还没有全文") LocalDateTime fullTextGeneratedAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {
}
