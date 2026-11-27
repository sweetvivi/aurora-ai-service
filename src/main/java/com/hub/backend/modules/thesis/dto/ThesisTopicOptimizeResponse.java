package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of {@code POST /api/v1/thesis/outlines/optimize}.
 *
 * @param original the title that came in
 * @param optimized the academic rewording handed back to the input box
 * @param reason    one line telling the user what changed, shown as a tooltip
 */
@Schema(name = "ThesisTopicOptimizeResult", description = "AI 题目优化结果")
public record ThesisTopicOptimizeResponse(
        @Schema(description = "原题目") String original,
        @Schema(description = "优化后的题目") String optimized,
        @Schema(description = "优化说明", example = "补充研究视角、方法与机理表述") String reason
) {
}
