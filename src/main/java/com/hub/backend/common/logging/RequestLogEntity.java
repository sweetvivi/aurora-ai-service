package com.hub.backend.common.logging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 一次 HTTP 请求的访问日志。
 *
 * <p>字段与 {@code V98__request_logs.sql} 严格一一对应：MySQL profile 下 {@code ddl-auto}
 * 是 {@code validate}，多一列少一列都会让应用起不来。改这里必须同时改迁移脚本。</p>
 *
 * <p>它同时充当写入载体：{@link RequestLogWriter} 不走 Hibernate 的 {@code save}，
 * 而是用 {@code JdbcTemplate.batchUpdate} 直接批量插入——日志场景下 entity 生命周期管理、
 * 一级缓存、flush 时序全是纯开销，且会跟业务事务纠缠。实体在这里只是因为两个现实原因：
 * H2 profile 关掉了 Flyway，没有实体就没有表；管理端查询也需要映射。</p>
 */
@Entity
@Table(name = "request_logs")
public class RequestLogEntity {

    /** 成功，HTTP 2xx/3xx。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** 调用方错误，HTTP 4xx。 */
    public static final String OUTCOME_CLIENT_ERROR = "CLIENT_ERROR";
    /** 服务端错误，HTTP 5xx。 */
    public static final String OUTCOME_SERVER_ERROR = "SERVER_ERROR";
    /** 请求被下游放行但上游挂了；比 {@link #OUTCOME_SERVER_ERROR} 更能指向根因。 */
    public static final String OUTCOME_UPSTREAM_ERROR = "UPSTREAM_ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 32)
    private String traceId;

    @Column(name = "request_time", nullable = false)
    private LocalDateTime requestTime;

    @Column(name = "level", nullable = false, length = 8)
    private String level;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "browser", length = 64)
    private String browser;

    @Column(name = "os", length = 64)
    private String os;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    /** 路由模板而非真实 URI，例如 {@code /api/v1/chat/requests/{requestId}/messages}。 */
    @Column(name = "route", length = 255)
    private String route;

    @Column(name = "uri", nullable = false, length = 512)
    private String uri;

    @Column(name = "query_string", length = 1024)
    private String queryString;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    /**
     * HTTP 状态码。用 {@code short} 而不是 {@code int}：库里是 {@code SMALLINT}，
     * MySQL profile 下 {@code ddl-auto=validate} 会逐列比对 JDBC 类型码，
     * {@code int} 对应 INTEGER、{@code short} 对应 SMALLINT，对不上就直接启动失败。
     * 状态码取值范围 100~599，SMALLINT 足够且比 INT 更省。
     */
    @Column(name = "response_status", nullable = false)
    private short responseStatus;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** 业务码，取自 {@code ApiError#code()}，前端就是按这个分流的。 */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    /** 与返回给调用方的 {@code ApiError#message()} 完全一致，便于「用户看到了什么」直接对照。 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 字段级校验明细，JSON 对象字符串。 */
    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    /** 本次请求触达的上游调用列表，JSON 数组。 */
    @Column(name = "upstream_json", columnDefinition = "TEXT")
    private String upstreamJson;

    @Column(name = "biz_type", length = 32)
    private String bizType;

    @Column(name = "biz_id", length = 64)
    private String bizId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RequestLogEntity() {
    }

    public RequestLogEntity(String traceId, LocalDateTime requestTime, String level) {
        this.traceId = traceId;
        this.requestTime = requestTime;
        this.level = level;
    }

    public Long getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public LocalDateTime getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(LocalDateTime requestTime) {
        this.requestTime = requestTime;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getBrowser() {
        return browser;
    }

    public void setBrowser(String browser) {
        this.browser = browser;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getQueryString() {
        return queryString;
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public short getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(short responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public void setErrorDetails(String errorDetails) {
        this.errorDetails = errorDetails;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public String getUpstreamJson() {
        return upstreamJson;
    }

    public void setUpstreamJson(String upstreamJson) {
        this.upstreamJson = upstreamJson;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
