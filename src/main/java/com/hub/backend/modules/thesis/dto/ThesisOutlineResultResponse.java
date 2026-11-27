package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of {@code POST /api/v1/thesis/outlines}.
 *
 * <p>Wraps the tree so the client can tell which engine produced it. That is not decoration: the
 * free tier is advertised as template based, and a caller who asked for a model needs to know
 * whether the answer actually came from one (and which), instead of guessing from the headings'
 * wording.</p>
 *
 * @param mode  {@code MODEL} when the headings came from a language model, {@code TEMPLATE} when the
 *              deterministic engine served the request
 * @param model model key that produced the outline, {@code null} in template mode
 */
@Schema(name = "ThesisOutlineResult", description = "大纲生成结果")
public record ThesisOutlineResultResponse(
        @Schema(description = "大纲树") List<ThesisOutlineNode> outline,
        @Schema(description = "生成方式：MODEL / TEMPLATE", example = "TEMPLATE") String mode,
        @Schema(description = "模型 key，模板模式下为 null", example = "deepseek-v4.1-flash") String model
) {

    public static final String MODE_MODEL = "MODEL";
    public static final String MODE_TEMPLATE = "TEMPLATE";
}
