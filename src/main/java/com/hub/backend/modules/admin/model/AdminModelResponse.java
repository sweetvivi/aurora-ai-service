package com.hub.backend.modules.admin.model;

import com.hub.backend.modules.model.dto.ModelCapabilities;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "AdminModel", description = "后台模型条目，来源和路由只读")
public record AdminModelResponse(
        @Schema(description = "模型 ID", example = "1") Long id,
        @Schema(description = "数据来源", example = "NEW_API") String source,
        @Schema(description = "New API 中的模型标识") String externalId,
        @Schema(description = "对外模型 key", example = "deepseek-v4.1-flash") String modelKey,
        @Schema(description = "展示名称") String modelName,
        @Schema(description = "模型类型：CHAT / IMAGE / VIDEO / AUDIO", example = "CHAT") String modelType,
        @Schema(description = "厂商") String company,
        @Schema(description = "真正发给上游的模型名；由 New API 同步，后台不可修改")
        String route,
        @Schema(description = "本地显示状态：ACTIVE 显示，DISABLED 隐藏", example = "ACTIVE") String status,
        @Schema(description = "New API 当前是否仍提供该模型") boolean upstreamAvailable,
        @Schema(description = "所属站点 ID，同步模型为 null 表示全局") Long siteId,
        @Schema(description = "分组 ID") Long groupId,
        @Schema(description = "分组 key") String groupKey,
        @Schema(description = "供应商 ID") Long providerId,
        @Schema(description = "供应商 key") String providerKey,
        @Schema(description = "供应商名称") String providerName,
        @Schema(description = "图标地址") String iconUrl,
        @Schema(description = "模型说明") String description,
        @Schema(description = "标签") List<String> tags,
        @Schema(description = "是否免费") boolean free,
        @Schema(description = "是否声明支持思考模式") boolean thinking,
        @Schema(description = "开启思考的上游请求字段名；未配置为 null", example = "enable_thinking")
        String thinkingParameter,
        @Schema(description = "开启思考时写入上游的值；未配置为 null（默认 true）", example = "true")
        String thinkingOnValue,
        @Schema(description = "关闭思考时写入上游的值；未配置为 null（默认 false）", example = "false")
        String thinkingOffValue,
        @Schema(description = "每次调用的积分单价") BigDecimal consumePoints,
        @Schema(description = "上下文窗口（token）；未登记为 null") Integer contextWindow,
        @Schema(description = "最大输出 token 数；未登记为 null") Integer maxOutputTokens,
        @Schema(description = "是否支持视觉输入") boolean supportsVision,
        @Schema(description = "是否支持工具调用") boolean supportsTools,
        @Schema(description = "是否支持上游原生联网搜索") boolean supportsWebSearch,
        @Schema(description = "每 1K 输入 token 的积分单价；null 表示未按方向分价") BigDecimal inputPointsPer1k,
        @Schema(description = "每 1K 输出 token 的积分单价；null 表示未按方向分价") BigDecimal outputPointsPer1k,
        @Schema(description = "排序号") int sortOrder,
        @Schema(description = "更新时间") LocalDateTime updatedAt,
        @Schema(description = "能力声明；未配置为 null。视频页的模式/画幅/分辨率/时长由此驱动")
        ModelCapabilities capabilities
) {
}
