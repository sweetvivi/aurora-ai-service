package com.hub.backend.modules.admin.logging;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.admin.operations.AdminListResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端「运行日志」查询。
 *
 * <p>为什么用 {@code NamedParameterJdbcTemplate} 而不是 JPA：过滤条件有 8 个维度且全部可选，
 * 用 JPA 要么上 Specification（一堆样板），要么拼 JPQL 字符串（同样要拼，还多一层映射）。
 * 日志表是纯追加、无关联、无懒加载的宽表，直接读列最省事。</p>
 *
 * <p>读路径不受异步写入影响：写入走 {@link com.hub.backend.common.logging.RequestLogWriter}
 * 的独立批量通道，这里只是普通只读查询，不会和业务事务抢锁。</p>
 */
@Service
@Transactional(readOnly = true)
public class AdminLogService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 列表里正文只取前 300 字符。请求体上限 8KB，一页 20 行不截断的话单次响应就是 160KB，
     * 而且管理端表格根本显示不了那么多——要看全文点详情。
     */
    private static final int PREVIEW_CHARS = 300;

    /** 未指定时间范围时的默认窗口。访问日志按 7 天清理，全表统计既慢又没有业务意义。 */
    private static final int DEFAULT_WINDOW_HOURS = 24;

    /**
     * 概览最多统计 30 天。访问日志只留 7 天，但概览同时统计审计表（保留 180 天），
     * 上限按审计表放宽；超出访问日志保留期的部分查出来只是空数据，不会更慢。
     */
    private static final int MAX_WINDOW_HOURS = 30 * 24;

    /*
     * 详情用的全量列。必须和列表预览列取同一批字段——少了任何一列，
     * 共用同一个 RowMapper 就会在 rs.getString(...) 上抛 SQLException（曾经漏过
     * request_body / error_details，表现是详情接口 500 而列表正常）。
     */
    private static final String REQUEST_COLUMNS = """
            id, trace_id, request_time, level, user_id, site_id, client_ip, user_agent, browser, os,
            http_method, route, uri, query_string, request_body, response_status, outcome,
            error_code, error_message, error_details, duration_ms, upstream_json, biz_type, biz_id
            """;

    private static final String REQUEST_PREVIEW_COLUMNS = """
            id, trace_id, request_time, level, user_id, site_id, client_ip, user_agent, browser, os,
            http_method, route, uri, query_string, response_status, outcome, error_code, error_message,
            duration_ms, biz_type, biz_id,
            SUBSTRING(request_body, 1, %d) AS request_body,
            SUBSTRING(error_details, 1, %d) AS error_details,
            SUBSTRING(upstream_json, 1, %d) AS upstream_json
            """.formatted(PREVIEW_CHARS, PREVIEW_CHARS, PREVIEW_CHARS);

    private static final String AUDIT_COLUMNS = """
            id, occurred_at, trace_id, action, target, target_id, actor_type, actor_id,
            client_ip, user_agent, http_method, route, changed_json, outcome, error_message, duration_ms,
            before_json, after_json
            """;

    private static final String AUDIT_PREVIEW_COLUMNS = """
            id, occurred_at, trace_id, action, target, target_id, actor_type, actor_id,
            client_ip, user_agent, http_method, route, outcome, error_message, duration_ms,
            SUBSTRING(changed_json, 1, %d) AS changed_json,
            SUBSTRING(before_json, 1, %d) AS before_json,
            SUBSTRING(after_json, 1, %d) AS after_json
            """.formatted(PREVIEW_CHARS, PREVIEW_CHARS, PREVIEW_CHARS);

    private final NamedParameterJdbcTemplate jdbc;

    public AdminLogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------ 访问日志 ------------------------------ */

    public AdminListResponse<AdminRequestLogResponse> requests(AdminLogQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = requestWhere(query, params);

        long total = count("SELECT COUNT(*) FROM request_logs" + where, params);
        if (total == 0) {
            return new AdminListResponse<>(List.of(), 0);
        }

        int page = Math.max(1, query.page());
        int pageSize = pageSize(query.pageSize());
        params.addValue("limit", pageSize);
        params.addValue("offset", (long) (page - 1) * pageSize);

        List<AdminRequestLogResponse> rows = jdbc.query(
                "SELECT " + REQUEST_PREVIEW_COLUMNS + " FROM request_logs" + where
                        + " ORDER BY request_time DESC, id DESC LIMIT :limit OFFSET :offset",
                params,
                AdminLogService::mapRequest);
        return new AdminListResponse<>(withUsernames(rows), total);
    }

    public AdminRequestLogResponse requestDetail(long id) {
        List<AdminRequestLogResponse> rows = jdbc.query(
                "SELECT " + REQUEST_COLUMNS + " FROM request_logs WHERE id = :id",
                new MapSqlParameterSource("id", id),
                AdminLogService::mapRequest);
        if (rows.isEmpty()) {
            throw new NotFoundException("日志记录不存在：id=" + id);
        }
        return withUsernames(rows).get(0);
    }

    private String requestWhere(AdminLogQuery query, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");

        String keyword = trimmed(query.keyword());
        if (keyword != null) {
            /*
             * 只搜这几个字段：body 是 TEXT，模糊匹配会全表扫；而排查时人记住的通常是接口名或报错。
             * 用户名也进搜索：日志表只有 user_id，让人先记住数字 id 再回来查是不现实的。
             * 子查询走 user_accounts.username，表小且经唯一索引约束，代价可接受。
             */
            where.append(" AND (route LIKE :keyword OR uri LIKE :keyword"
                    + " OR trace_id LIKE :keyword OR error_message LIKE :keyword"
                    + " OR user_id IN (SELECT id FROM user_accounts WHERE username LIKE :keyword))");
            params.addValue("keyword", "%" + keyword + "%");
        }
        String level = trimmed(query.level());
        if (level != null) {
            where.append(" AND level = :level");
            params.addValue("level", level.toUpperCase());
        }
        String outcome = trimmed(query.outcome());
        if (outcome != null) {
            where.append(" AND outcome = :outcome");
            params.addValue("outcome", outcome.toUpperCase());
        }
        String method = trimmed(query.method());
        if (method != null) {
            where.append(" AND http_method = :method");
            params.addValue("method", method.toUpperCase());
        }
        if (query.userId() != null) {
            where.append(" AND user_id = :userId");
            params.addValue("userId", query.userId());
        }
        String traceId = trimmed(query.traceId());
        if (traceId != null) {
            where.append(" AND trace_id = :traceId");
            params.addValue("traceId", traceId);
        }
        appendStatusClass(where, query.status());
        appendTimeRange(where, params, query.from(), query.to(), "request_time");
        return where.toString();
    }

    /** success = 非错误；client = 4xx；server = 5xx。比让人手填「≥400」更不容易填错。 */
    private void appendStatusClass(StringBuilder where, String status) {
        String value = trimmed(status);
        if (value == null) {
            return;
        }
        switch (value.toLowerCase()) {
            case "success" -> where.append(" AND response_status < 400");
            case "client" -> where.append(" AND response_status >= 400 AND response_status < 500");
            case "server" -> where.append(" AND response_status >= 500");
            default -> throw new InvalidRequestException(
                    "status 只支持 success / client / server，收到：" + status);
        }
    }

    /* ------------------------------ 审计日志 ------------------------------ */

    public AdminListResponse<AdminAuditLogResponse> audits(AdminAuditLogQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = auditWhere(query, params);

        long total = count("SELECT COUNT(*) FROM audit_logs" + where, params);
        if (total == 0) {
            return new AdminListResponse<>(List.of(), 0);
        }

        int page = Math.max(1, query.page());
        int pageSize = pageSize(query.pageSize());
        params.addValue("limit", pageSize);
        params.addValue("offset", (long) (page - 1) * pageSize);

        List<AdminAuditLogResponse> rows = jdbc.query(
                "SELECT " + AUDIT_PREVIEW_COLUMNS + " FROM audit_logs" + where
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset",
                params,
                AdminLogService::mapAudit);
        return new AdminListResponse<>(withActorUsernames(rows), total);
    }

    public AdminAuditLogResponse auditDetail(long id) {
        List<AdminAuditLogResponse> rows = jdbc.query(
                "SELECT " + AUDIT_COLUMNS + " FROM audit_logs WHERE id = :id",
                new MapSqlParameterSource("id", id),
                AdminLogService::mapAudit);
        if (rows.isEmpty()) {
            throw new NotFoundException("审计记录不存在：id=" + id);
        }
        return withActorUsernames(rows).get(0);
    }

    private String auditWhere(AdminAuditLogQuery query, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");

        String keyword = trimmed(query.keyword());
        if (keyword != null) {
            // actor_id 是字符串列，用户类型时存的就是 user id；再按用户名反查一遍，
            // 这样「谁改了什么」可以直接按人名搜。
            where.append(" AND (action LIKE :keyword OR target LIKE :keyword"
                    + " OR target_id LIKE :keyword OR actor_id LIKE :keyword"
                    + " OR actor_id IN (SELECT CAST(id AS CHAR) FROM user_accounts"
                    + " WHERE username LIKE :keyword))");
            params.addValue("keyword", "%" + keyword + "%");
        }
        String action = trimmed(query.action());
        if (action != null) {
            where.append(" AND action = :action");
            params.addValue("action", action);
        }
        String target = trimmed(query.target());
        if (target != null) {
            where.append(" AND target = :target");
            params.addValue("target", target);
        }
        String outcome = trimmed(query.outcome());
        if (outcome != null) {
            where.append(" AND outcome = :outcome");
            params.addValue("outcome", outcome.toUpperCase());
        }
        appendTimeRange(where, params, query.from(), query.to(), "occurred_at");
        return where.toString();
    }

    /* ------------------------------ 概览统计 ------------------------------ */

    public AdminLogOverviewResponse overview(String fromRaw, String toRaw, Integer hours) {
        LocalDateTime to = parseTime(toRaw);
        LocalDateTime from = parseTime(fromRaw);
        if (to == null) {
            to = LocalDateTime.now();
        }
        if (from == null) {
            int windowHours = hours == null || hours <= 0
                    ? DEFAULT_WINDOW_HOURS
                    : Math.min(hours, MAX_WINDOW_HOURS);
            from = to.minusHours(windowHours);
        }
        if (!from.isBefore(to)) {
            throw new InvalidRequestException("开始时间必须早于结束时间");
        }

        MapSqlParameterSource params = new MapSqlParameterSource("from", from).addValue("to", to);

        AdminLogOverviewResponse.Requests requests = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN response_status >= 400 THEN 1 ELSE 0 END) AS errors,
                       SUM(CASE WHEN response_status >= 400 AND response_status < 500 THEN 1 ELSE 0 END) AS client_errors,
                       SUM(CASE WHEN response_status >= 500 THEN 1 ELSE 0 END) AS server_errors,
                       AVG(duration_ms) AS avg_ms,
                       COALESCE(MAX(duration_ms), 0) AS max_ms
                FROM request_logs
                WHERE request_time >= :from AND request_time < :to
                """, params, (rs, i) -> {
            long total = rs.getLong("total");
            long errors = rs.getLong("errors");
            double errorRate = total == 0 ? 0 : round(errors * 100.0 / total, 2);
            return new AdminLogOverviewResponse.Requests(
                    total,
                    errors,
                    rs.getLong("client_errors"),
                    rs.getLong("server_errors"),
                    errorRate,
                    round(rs.getDouble("avg_ms"), 1),
                    rs.getLong("max_ms"));
        });

        List<AdminLogOverviewResponse.Bucket> outcomes = buckets("""
                SELECT outcome AS k, COUNT(*) AS c
                FROM request_logs
                WHERE request_time >= :from AND request_time < :to
                GROUP BY outcome
                ORDER BY c DESC
                """, params);

        List<AdminLogOverviewResponse.RouteStat> topRoutes = jdbc.query("""
                SELECT route, COUNT(*) AS c,
                       SUM(CASE WHEN response_status >= 400 THEN 1 ELSE 0 END) AS errors,
                       AVG(duration_ms) AS avg_ms
                FROM request_logs
                WHERE request_time >= :from AND request_time < :to AND route IS NOT NULL
                GROUP BY route
                ORDER BY c DESC
                LIMIT 8
                """, params, AdminLogService::mapRouteStat);

        // 只统计调用量 >= 3 的接口：一条偶发的慢请求排到榜首，对排查是噪音。
        List<AdminLogOverviewResponse.RouteStat> slowRoutes = jdbc.query("""
                SELECT route, COUNT(*) AS c,
                       SUM(CASE WHEN response_status >= 400 THEN 1 ELSE 0 END) AS errors,
                       AVG(duration_ms) AS avg_ms
                FROM request_logs
                WHERE request_time >= :from AND request_time < :to AND route IS NOT NULL
                GROUP BY route
                HAVING COUNT(*) >= 3
                ORDER BY avg_ms DESC
                LIMIT 8
                """, params, AdminLogService::mapRouteStat);

        List<AdminLogOverviewResponse.Bucket> errorCodes = buckets("""
                SELECT error_code AS k, COUNT(*) AS c
                FROM request_logs
                WHERE request_time >= :from AND request_time < :to AND error_code IS NOT NULL
                GROUP BY error_code
                ORDER BY c DESC
                LIMIT 8
                """, params);

        AdminLogOverviewResponse.Audits audits = jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN outcome = 'FAILURE' THEN 1 ELSE 0 END) AS failures
                FROM audit_logs
                WHERE occurred_at >= :from AND occurred_at < :to
                """, params, (rs, i) -> new AdminLogOverviewResponse.Audits(
                rs.getLong("total"),
                rs.getLong("failures"),
                null));

        List<AdminLogOverviewResponse.Bucket> auditActions = buckets("""
                SELECT action AS k, COUNT(*) AS c
                FROM audit_logs
                WHERE occurred_at >= :from AND occurred_at < :to
                GROUP BY action
                ORDER BY c DESC
                LIMIT 8
                """, params);

        return new AdminLogOverviewResponse(
                from.toString(),
                to.toString(),
                requests,
                outcomes,
                topRoutes,
                slowRoutes,
                errorCodes,
                new AdminLogOverviewResponse.Audits(audits.total(), audits.failures(), auditActions));
    }

    /* ------------------------------ 公共工具 ------------------------------ */

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private List<AdminLogOverviewResponse.Bucket> buckets(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, i) -> new AdminLogOverviewResponse.Bucket(
                rs.getString("k"), rs.getLong("c")));
    }

    private static AdminLogOverviewResponse.RouteStat mapRouteStat(ResultSet rs, int rowNum)
            throws SQLException {
        return new AdminLogOverviewResponse.RouteStat(
                rs.getString("route"),
                rs.getLong("c"),
                rs.getLong("errors"),
                round(rs.getDouble("avg_ms"), 1));
    }

    private static AdminRequestLogResponse mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new AdminRequestLogResponse(
                rs.getLong("id"),
                rs.getString("trace_id"),
                rs.getObject("request_time", LocalDateTime.class),
                rs.getString("level"),
                nullableLong(rs, "user_id"),
                // 用户名不在日志表里，由 withUsernames 批量补；这里先留空。
                null,
                nullableLong(rs, "site_id"),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                rs.getString("browser"),
                rs.getString("os"),
                rs.getString("http_method"),
                rs.getString("route"),
                rs.getString("uri"),
                rs.getString("query_string"),
                rs.getString("request_body"),
                // 列是 SMALLINT，实体用 short 与之对应；这里是只读映射，转 int 给前端更方便判断区间。
                rs.getShort("response_status"),
                rs.getString("outcome"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("error_details"),
                rs.getInt("duration_ms"),
                rs.getString("upstream_json"),
                rs.getString("biz_type"),
                rs.getString("biz_id"));
    }

    private static AdminAuditLogResponse mapAudit(ResultSet rs, int rowNum) throws SQLException {
        return new AdminAuditLogResponse(
                rs.getLong("id"),
                rs.getObject("occurred_at", LocalDateTime.class),
                rs.getString("trace_id"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("target_id"),
                rs.getString("actor_type"),
                rs.getString("actor_id"),
                // 同访问日志：用户名在查询后按 actor_type=USER 批量补。
                null,
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                rs.getString("http_method"),
                rs.getString("route"),
                rs.getString("before_json"),
                rs.getString("after_json"),
                rs.getString("changed_json"),
                rs.getString("outcome"),
                rs.getString("error_message"),
                rs.getInt("duration_ms"));
    }

    /**
     * 把本页出现过的 {@code user_id} 一次性换成用户名。
     *
     * <p>日志表只存 id，但排查时人记的是用户名，一列数字对不上是谁。按页内去重后的 id
     * 集合查一次 {@code user_accounts}，而不是每行一次（一页 200 行就是 200 次往返）。</p>
     *
     * <p>查不到名字不是错误：账号注销后日志仍按保留期存在，这类行补成 {@code null}，
     * 前端退化成显示 id。</p>
     */
    private List<AdminRequestLogResponse> withUsernames(List<AdminRequestLogResponse> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AdminRequestLogResponse row : rows) {
            if (row.userId() != null) {
                ids.add(row.userId());
            }
        }
        if (ids.isEmpty()) {
            return rows;
        }
        Map<Long, String> names = usernames(ids);
        return rows.stream()
                .map(row -> row.userId() == null ? row : row.withUsername(names.get(row.userId())))
                .toList();
    }

    /** 审计同理，但只有 {@code USER} 类型的 {@code actorId} 才是用户 id。 */
    private List<AdminAuditLogResponse> withActorUsernames(List<AdminAuditLogResponse> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AdminAuditLogResponse row : rows) {
            Long id = userActorId(row);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return rows;
        }
        Map<Long, String> names = usernames(ids);
        return rows.stream()
                .map(row -> {
                    Long id = userActorId(row);
                    return id == null ? row : row.withActorUsername(names.get(id));
                })
                .toList();
    }

    private static Long userActorId(AdminAuditLogResponse row) {
        if (!"USER".equalsIgnoreCase(row.actorType()) || row.actorId() == null) {
            return null;
        }
        try {
            return Long.parseLong(row.actorId().strip());
        } catch (NumberFormatException e) {
            // actor_id 是字符串列，出现非数字就说明它不是用户 id，不该拿去查表。
            return null;
        }
    }

    private Map<Long, String> usernames(Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>(Math.max(4, ids.size() * 2));
        jdbc.query("SELECT id, username FROM user_accounts WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("username"));
                });
        return names;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static int pageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private static double round(double value, int scale) {
        if (value == 0) {
            return 0;
        }
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * 时间范围过滤。两个都不给时默认近 24 小时——一次无范围查询会扫全表。
     * 只给 to 时回退 24 小时窗口，只给 from 时视为「至今」。
     *
     * @param timeColumn 列名由调用方传入：request_logs 用 {@code request_time}，
     *                   audit_logs 用 {@code occurred_at}。两张表的时间列不同名，
     *                   写死会出现「审计页永远查不到数据」这种静默错误。
     */
    private void appendTimeRange(StringBuilder where,
                                 MapSqlParameterSource params,
                                 String fromRaw,
                                 String toRaw,
                                 String timeColumn) {
        LocalDateTime to = parseTime(toRaw);
        LocalDateTime from = parseTime(fromRaw);
        if (from == null && to == null) {
            to = LocalDateTime.now();
            from = to.minusHours(DEFAULT_WINDOW_HOURS);
        } else if (from == null) {
            from = to.minusHours(DEFAULT_WINDOW_HOURS);
        } else if (to == null) {
            to = LocalDateTime.now().plusSeconds(1);
        }
        where.append(" AND ").append(timeColumn)
                .append(" >= :fromTime AND ").append(timeColumn).append(" < :toTime");
        params.addValue("fromTime", from);
        params.addValue("toTime", to);
    }

    /**
     * 支持三种写法：{@code 2026-11-10}（按当天 00:00）、{@code 2026-11-10T10:00}、
     * {@code 2026-11-10T10:00:00}，以及浏览器 {@code toISOString()} 产出的带 Z 的 UTC 串。
     */
    static LocalDateTime parseTime(String raw) {
        String value = trimmed(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value.endsWith("Z")) {
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime();
            }
            String normalized = value.replace(' ', 'T');
            if (normalized.length() == 10) {
                return LocalDate.parse(normalized).atStartOfDay();
            }
            if (normalized.length() == 16) {
                normalized = normalized + ":00";
            }
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new InvalidRequestException(
                    "时间格式无法解析：" + raw + "，请使用 2026-11-10T10:00:00");
        }
    }
}
