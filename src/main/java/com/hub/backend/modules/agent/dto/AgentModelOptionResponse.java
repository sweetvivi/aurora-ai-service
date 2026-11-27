package com.hub.backend.modules.agent.dto;

import com.hub.backend.modules.model.entity.LlmModelEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 智能体下拉框里的一个模型选项。
 *
 * <p>{@link AgentResponse#modelIds()} 只有数字 ID，前端拿不到可读的名字，只能显示
 * 「模型 1 / 模型 2」。这个 DTO 把展示需要的字段一起下发，让 {@code /super-agent/:id}
 * 的右上角能直接渲染出「DeepSeek V4 Pro」这种选项。</p>
 *
 * <p>只带展示与提示用的字段：上游 {@code route} 属于供应商实现细节，不下发。</p>
 */
@Schema(name = "AgentModelOption", description = "智能体可用模型")
public record AgentModelOptionResponse(
        @Schema(description = "模型 ID，调用对话接口时作为 model 参数回传", example = "4") Long id,
        @Schema(description = "模型 key", example = "deepseek-v4-pro-0813") String modelKey,
        @Schema(description = "展示名称", example = "DeepSeek V4 Pro (0813)") String modelName,
        @Schema(description = "图标地址，未设置为 null") String iconUrl,
        @Schema(description = "是否为该智能体的默认模型") boolean defaultModel,
        @Schema(description = "是否支持视觉输入", example = "false") boolean supportsVision,
        @Schema(description = "是否支持工具调用", example = "true") boolean supportsTools
) {

    public static AgentModelOptionResponse of(LlmModelEntity model, boolean defaultModel) {
        return of(model, model.getModelKey(), model.getModelName(), defaultModel);
    }

    public static AgentModelOptionResponse of(LlmModelEntity model,
                                              String modelKey,
                                              String modelName,
                                              boolean defaultModel) {
        return new AgentModelOptionResponse(
                model.getId(),
                modelKey,
                modelName,
                model.getIconUrl(),
                defaultModel,
                model.isSupportsVision(),
                model.isSupportsTools());
    }
}
