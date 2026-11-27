package com.hub.backend.modules.gateway.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TokenConsumptionLogResponse(
        int page,
        int pageSize,
        long total,
        List<Item> items
) {
    /**
     * One ledger row.
     *
     * @param type 行的方向与种类，取自 New API 的 {@code LogType}：
     *             {@code consume}（扣费）、{@code refund}（失败任务退款）、{@code topup}（充值）、
     *             {@code system} / {@code error} / {@code manage}（运维行，金额恒为 0）。
     *             金额本身始终为正数，方向由本字段决定，前端据此渲染 {@code +} 号与颜色。
     */
    public record Item(
            long id,
            Instant createdAt,
            String modelName,
            String tokenName,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long quota,
            BigDecimal amountCny,
            long useTime,
            boolean stream,
            String type
    ) {
    }
}
