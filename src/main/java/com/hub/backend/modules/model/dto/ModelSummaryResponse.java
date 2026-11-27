package com.hub.backend.modules.model.dto;

import com.hub.backend.modules.model.entity.LlmModelEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * One model as shown in the catalogue.
 *
 * <p>Built from {@link LlmModelEntity} by {@code ModelCatalogService}, which copies display fields
 * only. The upstream {@code route} is deliberately absent: it names the provider specific model and
 * is not part of the public contract.</p>
 */
@Schema(name = "ModelSummary", description = "模型目录条目")
public record ModelSummaryResponse(
        @Schema(description = "同版本模型组 key，调用模型接口时传该值", example = "deepseek-v4.1-flash")
        String modelKey,
        @Schema(description = "同版本模型组展示名称", example = "DeepSeek V4.1 Flash") String modelName,
        @Schema(description = "模型类型：CHAT / IMAGE / VIDEO / AUDIO", example = "CHAT") String modelType,
        @Schema(description = "厂商", example = "DeepSeek") String company,
        @Schema(description = "所属同版本模型组 key", example = "deepseek-v4.1-flash") String groupKey,
        @Schema(description = "图标地址，未设置为 null") String iconUrl,
        @Schema(description = "模型说明，未设置为 null") String description,
        @Schema(description = "标签，按展示顺序") List<String> tags,
        @Schema(description = "是否免费", example = "false") boolean free,
        @Schema(description = "是否支持客户端开启思考模式；需存在 thinking 参数配置", example = "true")
        boolean thinking,
        @Schema(description = "是否仅支持积分使用", example = "false") boolean pointsOnly,
        @Schema(description = "是否允许积分抵扣", example = "true") boolean pointsAllowed,
        @Schema(description = "每次调用的积分单价", example = "0.010000") BigDecimal consumePoints,
        @Schema(description = "上下文窗口（token）；未登记为 null", example = "128000") Integer contextWindow,
        @Schema(description = "最大输出 token 数；未登记为 null", example = "8192") Integer maxOutputTokens,
        @Schema(description = "是否支持视觉输入", example = "false") boolean supportsVision,
        @Schema(description = "是否支持工具调用", example = "true") boolean supportsTools,
        @Schema(description = "是否支持上游原生联网搜索（tools type=web_search）", example = "false")
        boolean supportsWebSearch,
        @Schema(description = "每 1K 输入 token 的积分单价；未按方向分价时为 null") BigDecimal inputPointsPer1k,
        @Schema(description = "每 1K 输出 token 的积分单价；未按方向分价时为 null") BigDecimal outputPointsPer1k,
        @Schema(description = "模型能力声明；null 表示未声明，客户端使用自身默认值")
        ModelCapabilities capabilities
) {

    public static ModelSummaryResponse of(LlmModelEntity model, String groupKey, List<String> tags,
                                          ModelCapabilities capabilities) {
        return ofGroup(model.getModelKey(), model.getModelName(), model, groupKey,
                model.getConsumePoints(), tags, capabilities);
    }

    /**
     * Public fallback-group view. Concrete member names and routes stay internal; the group key,
     * display name and locally configured points price are what the storefront sees. Anonymous
     * upstream prices are used for ordering, not as a replacement for the local billing field.
     */
    public static ModelSummaryResponse ofGroup(
            String modelKey,
            String modelName,
            LlmModelEntity representative,
            String groupKey,
            BigDecimal consumePoints,
            List<String> tags,
            ModelCapabilities capabilities) {
        return new ModelSummaryResponse(
                modelKey,
                modelName,
                representative.getModelType(),
                representative.getCompany(),
                groupKey,
                representative.getIconUrl(),
                representative.getDescription(),
                tags,
                representative.isFree(),
                com.hub.backend.modules.model.ChatThinkingDefaults.exposesSwitch(
                        representative.getModelKey(), representative.isThinking(), capabilities),
                representative.isPointsOnly(),
                representative.isPointsAllowed(),
                consumePoints,
                representative.getContextWindow(),
                representative.getMaxOutputTokens(),
                representative.isSupportsVision(),
                representative.isSupportsTools(),
                representative.isSupportsWebSearch(),
                representative.getInputPointsPer1k(),
                representative.getOutputPointsPer1k(),
                capabilities);
    }
}
