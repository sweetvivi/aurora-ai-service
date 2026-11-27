package com.hub.backend.modules.admin.model;

import com.hub.backend.modules.model.price.ModelPriceView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(name = "AdminModelFallbackMember",
        description = "版本组中的具体模型及当前可执行状态；价格以 pricing 为准，"
                + "effectivePrice 仅保留给旧前端做排序兜底")
public record AdminModelFallbackMemberResponse(
        Long id,
        Long modelId,
        String modelKey,
        String modelName,
        String modelType,
        String company,
        String route,
        Long siteId,
        String status,
        boolean upstreamAvailable,
        BigDecimal effectivePrice,
        ModelPriceView pricing,
        int priority,
        boolean enabled,
        boolean eligible,
        String ineligibleReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
