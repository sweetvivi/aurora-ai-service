package com.hub.backend.modules.admin.logging;

/**
 * 审计日志的查询条件。所有字段都可为空，为空即不过滤。
 *
 * @param keyword 模糊匹配 action / target / target_id / actor_id
 * @param action  精确匹配，例如 UPDATE_MODEL_VISIBILITY
 * @param target  精确匹配，例如 LLM_MODEL
 * @param outcome SUCCESS / FAILURE
 */
public record AdminAuditLogQuery(
        String keyword,
        String action,
        String target,
        String outcome,
        String from,
        String to,
        int page,
        int pageSize) {
}
