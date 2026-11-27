package com.hub.backend.modules.admin.logging;

import java.util.List;

/**
 * 运行日志页顶部的概览统计。
 *
 * <p>所有数字都限定在同一个时间窗内（前端传 from/to），避免「卡片是今天、表格是近 7 天」
 * 这类看起来对不上的问题。</p>
 */
public record AdminLogOverviewResponse(
        String from,
        String to,
        Requests requests,
        List<Bucket> outcomes,
        List<RouteStat> topRoutes,
        List<RouteStat> slowRoutes,
        List<Bucket> errorCodes,
        Audits audits) {

    /** {@code errorRate} 是百分比（0~100），不是 0~1。 */
    public record Requests(
            long total,
            long errors,
            long clientErrors,
            long serverErrors,
            double errorRate,
            double avgMs,
            long maxMs) {
    }

    /** 通用「值 + 次数」桶：outcome 分布、错误码分布、审计动作分布都用它。 */
    public record Bucket(String key, long count) {
    }

    /** {@code avgMs} 保留一位小数即可，前端不画需要高精度的图。 */
    public record RouteStat(String route, long count, long errors, double avgMs) {
    }

    public record Audits(long total, long failures, List<Bucket> actions) {
    }
}
