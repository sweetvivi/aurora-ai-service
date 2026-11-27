package com.hub.backend.modules.gateway.dto;

import java.time.LocalDate;
import java.util.List;

public record TokenUsageHistoryResponse(
        int days,
        LocalDate from,
        LocalDate to,
        long totalPromptTokens,
        long totalCompletionTokens,
        long totalTokens,
        List<DailyUsage> daily
) {
    public record DailyUsage(
            LocalDate date,
            long promptTokens,
            long completionTokens,
            long totalTokens
    ) {
    }
}
