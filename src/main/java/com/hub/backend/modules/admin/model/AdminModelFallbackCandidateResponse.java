package com.hub.backend.modules.admin.model;

import com.hub.backend.modules.model.price.ModelPriceView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(name = "AdminModelFallbackCandidate",
        description = "可加入版本组的模型；不限制必须属于同一系列。价格以 pricing 为准")
public record AdminModelFallbackCandidateResponse(
        Long modelId,
        String modelKey,
        String modelName,
        String modelType,
        String company,
        String route,
        Long siteId,
        boolean global,
        BigDecimal effectivePrice,
        ModelPriceView pricing,
        /**
         * 上游（New API）为这个模型开启的分组，按上游声明顺序。
         *
         * <p>分组决定价格倍率，运营挑模型时靠它判断「同名模型是不是走了贵分组」。
         * 空列表表示上游定价目录里没有这个模型，或没有声明分组。</p>
         */
        List<String> upstreamGroups,
        boolean thinking,
        boolean supportsVision,
        boolean supportsTools
) {
    public AdminModelFallbackCandidateResponse {
        upstreamGroups = upstreamGroups == null ? List.of() : List.copyOf(upstreamGroups);
    }
}
