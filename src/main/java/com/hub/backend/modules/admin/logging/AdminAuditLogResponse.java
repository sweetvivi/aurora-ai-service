package com.hub.backend.modules.admin.logging;

import java.time.LocalDateTime;

/**
 * 一条审计日志（{@code audit_logs}）的管理端视图。
 *
 * <p>核心是三段 JSON：{@code beforeJson} / {@code afterJson} 是变更前后的快照，
 * {@code changedJson} 是字段级差异（{@code {"status":{"from":"ACTIVE","to":"DISABLED"}}}）。
 * 列表里只取差异和快照的前 300 字符，详情接口返回完整内容。</p>
 *
 * <p>{@code actorId} 是操作人标识，具体是什么由 {@code actorType} 决定：{@code ADMIN_KEY}
 * 时是 {@code admin-key}，{@code SYSTEM} 时是任务名，只有 {@code USER} 时才是用户 id。
 * {@code actorUsername} 只对最后一种有意义——查询时按本页 id 批量补，账号已注销则为
 * {@code null}。</p>
 */
public record AdminAuditLogResponse(
        Long id,
        LocalDateTime occurredAt,
        String traceId,
        String action,
        String target,
        String targetId,
        String actorType,
        String actorId,
        /** {@code actorType=USER} 时的当前用户名，其余类型恒为 {@code null}。 */
        String actorUsername,
        String clientIp,
        String userAgent,
        String httpMethod,
        String route,
        String beforeJson,
        String afterJson,
        String changedJson,
        String outcome,
        String errorMessage,
        int durationMs) {

    /** 补上操作人用户名后的同一条记录；{@code actorType} 不是 {@code USER} 时原样返回。 */
    public AdminAuditLogResponse withActorUsername(String resolvedUsername) {
        return new AdminAuditLogResponse(
                id, occurredAt, traceId, action, target, targetId, actorType, actorId, resolvedUsername,
                clientIp, userAgent, httpMethod, route, beforeJson, afterJson, changedJson, outcome,
                errorMessage, durationMs);
    }
}
