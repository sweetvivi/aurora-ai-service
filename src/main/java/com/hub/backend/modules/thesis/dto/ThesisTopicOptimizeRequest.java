package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/thesis/outlines/optimize}.
 *
 * @param topic  the draft title the user typed
 * @param major  optional major, used to pick the rewording rule
 */
@Schema(name = "ThesisTopicOptimizeRequest", description = "AI 题目优化请求")
public record ThesisTopicOptimizeRequest(
        @Schema(description = "原始题目，可为空；为空时按专业生成一个", example = "短视频推荐算法优化")
        String topic,
        @Schema(description = "二级专业，用于选择改写规则", example = "计算机科学与技术") String major
) {
}
