package com.hub.backend.modules.admin.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminDashboardStatsResponse(
        long totalVisits,
        long totalTokens,
        NewUserTrend newUsers,
        Instant generatedAt
) {
    public record NewUserTrend(int days, List<DailyNewUser> daily) {
    }

    public record DailyNewUser(LocalDate date, long count) {
    }
}
