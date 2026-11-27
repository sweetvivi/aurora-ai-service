package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * One tool click from the page.
 *
 * @param action  tool key rendered by the page, e.g. {@code summary} / {@code extract}
 * @param options tool specific knobs: {@code style} (总结 / 摘要风格), {@code language} (翻译目标语言),
 *                {@code instruction} (润色要求). Unknown keys are ignored on purpose — the page
 *                sends one shared shape for every tool.
 */
@Schema(name = "DocumentActionRequest", description = "执行一个文档处理动作")
public record DocumentActionRequest(
        @Schema(description = "动作标识", example = "summary") String action,
        @Schema(description = "动作可选参数，例如 {style: deep}、{language: 英文}") Map<String, String> options
) {

    public DocumentActionRequest {
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public String option(String key, String fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
