package com.hub.backend.common.logging.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一条审计记录：某个写操作把某个对象从「before」改成了「after」。
 *
 * <p>字段与 {@code V99__audit_logs.sql} 严格一一对应。MySQL profile 下 {@code ddl-auto}
 * 是 {@code validate}，改实体必须同步改迁移脚本，否则应用直接起不来。</p>
 *
 * <p>与 {@code RequestLogEntity} 一样，实体在这里只是因为 H2 profile 关掉了 Flyway
 * （没有实体就没有表），实际写入走 {@link AuditLogWriter} 的 JDBC 批量插入，不走 Hibernate。</p>
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    /** 操作成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** 操作抛异常，或返回了表示失败的结果。失败同样要留痕：被拒的删除也是审计对象。 */
    public static final String OUTCOME_FAILURE = "FAILURE";

    /** 管理端固定密钥调用。没有操作人账号，只能记到 key 这一级。 */
    public static final String ACTOR_ADMIN = "ADMIN_KEY";
    /** 登录用户操作自己的数据（兑换码充值、注销等）。 */
    public static final String ACTOR_USER = "USER";
    /** 定时任务、启动流程等没有请求上下文的写入。 */
    public static final String ACTOR_SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 与 request_logs 的 trace_id 同一套值，用来从审计记录跳到当次请求的全部日志。 */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target", nullable = false, length = 64)
    private String target;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "route", length = 255)
    private String route;

    /** 变更前快照，JSON。null 表示「新增」或未配置。 */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    /** 字段级差异，形如 {"visibility":{"from":"ACTIVE","to":"DISABLED"}}。 */
    @Column(name = "changed_json", columnDefinition = "TEXT")
    private String changedJson;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
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

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public String getChangedJson() {
        return changedJson;
    }

    public void setChangedJson(String changedJson) {
        this.changedJson = changedJson;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(int durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
