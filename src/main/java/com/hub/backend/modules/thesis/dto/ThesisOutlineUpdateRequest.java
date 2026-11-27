package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code PUT /api/v1/thesis/documents/{id}/outline}.
 *
 * <p>The whole tree is replaced in one request: rename, insert and delete all reduce to "send the
 * tree you want". That keeps the editor free of per-node endpoints and makes a save atomic.</p>
 *
 * @param outline    new tree, top level items must be chapters (level 1)
 * @param topic      optional new topic, null keeps the stored one
 * @param targetWords optional new word count, null keeps the stored one
 */
@Schema(name = "ThesisOutlineUpdateRequest", description = "保存大纲（整树覆盖）")
public record ThesisOutlineUpdateRequest(
        @Schema(description = "新的三级大纲树", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ThesisOutlineNode> outline,

        @Schema(description = "可选：同时修改题目", example = "基于多模态融合的金融舆情分析研究")
        @Size(max = 60, message = "topic must be at most 60 characters")
        String topic,

        @Schema(description = "可选：同时修改目标字数", example = "12000")
        Integer targetWords
) {
}
