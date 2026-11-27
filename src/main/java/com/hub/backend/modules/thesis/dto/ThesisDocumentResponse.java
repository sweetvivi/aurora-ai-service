package com.hub.backend.modules.thesis.dto;

import com.hub.backend.modules.thesis.entity.ThesisDocumentEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A thesis draft as returned by every {@code /api/v1/thesis} endpoint.
 *
 * @param nodeCount     total number of headings, chapters included
 * @param estimatedWords {@code nodeCount} times the per-heading budget the outline was built with
 */
@Schema(name = "ThesisDocument", description = "论文稿件")
public record ThesisDocumentResponse(
        @Schema(description = "稿件 ID", example = "1") Long id,
        @Schema(description = "论文题目") String topic,
        @Schema(description = "学历层次", example = "本科在读") String educationLevel,
        @Schema(description = "文本类型", example = "论文助手") String paperType,
        @Schema(description = "大纲结构：2 二级 / 3 三级", example = "3") int outlineDepth,
        @Schema(description = "大纲模板（内部标识）", example = "UNDERGRAD") String tier,
        @Schema(description = "一级学科") String discipline,
        @Schema(description = "二级专业") String major,
        @Schema(description = "目标字数", example = "8000") int targetWords,
        @Schema(description = "是否引用近三年真实文献") boolean referenceEnabled,
        @Schema(description = "是否同步生成开题报告与答辩 PPT 提纲") boolean attachmentsEnabled,
        @Schema(description = "是否附带数据分析模型与代码实现") boolean dataModelEnabled,
        @Schema(description = "内容状态：OUTLINE / GENERATING / COMPLETED") String status,
        @Schema(description = "大纲版本号，每次保存加一", example = "3") int outlineVersion,
        @Schema(description = "节点总数", example = "45") int nodeCount,
        @Schema(description = "预估成文字数", example = "27900") int estimatedWords,
        @Schema(description = "是否已保存过全文（可直接在阅读器里打开）") boolean hasFullText,
        @Schema(description = "已保存正文的字数，0 表示还没有全文", example = "8120") int fullTextWordCount,
        @Schema(description = "全文生成时间；为空表示还没有全文") LocalDateTime fullTextGeneratedAt,
        @Schema(description = "三级大纲树") List<ThesisOutlineNode> outline,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    public static ThesisDocumentResponse of(ThesisDocumentEntity entity, List<ThesisOutlineNode> outline) {
        int nodeCount = countNodes(outline);
        return new ThesisDocumentResponse(
                entity.getId(),
                entity.getTopic(),
                entity.getEducationLevel(),
                entity.getPaperType(),
                entity.getOutlineDepth(),
                entity.getTier(),
                entity.getDiscipline(),
                entity.getMajor(),
                entity.getTargetWords(),
                entity.isReferenceEnabled(),
                entity.isAttachmentsEnabled(),
                entity.isDataModelEnabled(),
                entity.getStatus(),
                entity.getOutlineVersion(),
                nodeCount,
                nodeCount * 620,
                entity.hasFullText(),
                entity.getFullTextWordCount(),
                entity.getFullTextGeneratedAt(),
                outline,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static int countNodes(List<ThesisOutlineNode> nodes) {
        if (nodes == null) {
            return 0;
        }
        int total = 0;
        for (ThesisOutlineNode node : nodes) {
            total += 1 + countNodes(node.children());
        }
        return total;
    }
}
