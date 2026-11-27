package com.hub.backend.modules.admin.logging;

import java.time.LocalDateTime;

/**
 * 一条访问日志（{@code request_logs}）的管理端视图。
 *
 * <p>列表与详情共用同一个结构，区别只在正文长度：列表里 {@code requestBody} /
 * {@code errorDetails} / {@code upstreamJson} 只取前 300 字符（一页 20 行，
 * 正文上限 8KB，不截断一次响应就有 160KB），详情接口返回完整内容。</p>
 *
 * <p>{@code route} 是路由模板（{@code /api/v1/chat/requests/{requestId}/messages}），
 * {@code uri} 是这次请求的真实地址。做聚合必须用前者，后者一条会话一个值，基数会爆。</p>
 *
 * <p>{@code username} 是 {@code userId} 的现查结果，不是 {@code request_logs} 的列：
 * 日志表只存 id（表是纯追加宽表，不挂外键），但排查时人记的是用户名，只给数字 id
 * 根本对不上是谁。查询时按本页出现过的 id 批量补一次，一条 SQL 解决，不会 N+1。</p>
 */
public record AdminRequestLogResponse(
        Long id,
        String traceId,
        LocalDateTime requestTime,
        String level,
        Long userId,
        /** 当前用户名；账号已注销时为 {@code null}，此时只剩 {@code userId} 可查。 */
        String username,
        Long siteId,
        String clientIp,
        String userAgent,
        String browser,
        String os,
        String httpMethod,
        String route,
        String uri,
        String queryString,
        String requestBody,
        int responseStatus,
        String outcome,
        String errorCode,
        String errorMessage,
        String errorDetails,
        int durationMs,
        String upstreamJson,
        String bizType,
        String bizId) {

    /** 补上用户名后的同一条记录。名字为 {@code null} 表示账号已注销，保留 id 供对照。 */
    public AdminRequestLogResponse withUsername(String resolvedUsername) {
        return new AdminRequestLogResponse(
                id, traceId, requestTime, level, userId, resolvedUsername, siteId, clientIp, userAgent,
                browser, os, httpMethod, route, uri, queryString, requestBody, responseStatus, outcome,
                errorCode, errorMessage, errorDetails, durationMs, upstreamJson, bizType, bizId);
    }
}
