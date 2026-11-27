package com.hub.backend.modules.admin.logging;

/**
 * 访问日志的查询条件。所有字段都可为空，为空即不过滤。
 *
 * @param keyword  模糊匹配 route / uri / trace_id / error_message
 * @param level    INFO / WARN / ERROR
 * @param outcome  SUCCESS / CLIENT_ERROR / SERVER_ERROR / UPSTREAM_ERROR
 * @param method   GET / POST / PUT / PATCH / DELETE
 * @param status   success(<400) / client(4xx) / server(5xx)
 * @param userId   精确匹配，用于「这个用户干了什么」
 * @param traceId  精确匹配，用于「这一次请求的完整记录」
 * @param page     从 1 开始
 */
public record AdminLogQuery(
        String keyword,
        String level,
        String outcome,
        String method,
        String status,
        Long userId,
        String traceId,
        String from,
        String to,
        int page,
        int pageSize) {
}
