package com.hub.backend.common.logging.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个写操作需要留审计记录，落到 {@code audit_logs} 表并同时打一条结构化日志。
 *
 * <p>用法（管理端写接口）：</p>
 * <pre>{@code
 * @AuditLog(action = "UPDATE_MODEL_VISIBILITY", target = "LLM_MODEL", id = "#id")
 * @PutMapping("/{id}/visibility")
 * public AdminModelResponse updateVisibility(@PathVariable Long id, ...) { ... }
 * }</pre>
 *
 * <p>涉及金额与凭据的操作，用 SpEL 把变更前后的值显式取出来，切面会自动算出字段级 diff：</p>
 * <pre>{@code
 * @AuditLog(action = "REDEEM_CODE", target = "USER_BALANCE", id = "#user.userId()",
 *           before = "@auditProbes.balance(#user.userId())",
 *           after = "@auditProbes.balance(#user.userId())")
 * }</pre>
 *
 * <p><b>为什么是注解而不是在业务方法里写日志</b>：审计的价值取决于覆盖率，而不是单点质量。
 * 业务代码里手写 {@code log.info} 会随着重构、复制粘贴慢慢漏掉；漏掉的那条恰恰可能是事故现场。
 * 注解写在接口方法上，一眼能看出哪些写操作有审计，评审时也容易发现新增接口没加。
 * 启动时 {@link AuditCoverageReporter} 还会把所有「管理端写接口但没加注解」的路径打出来。</p>
 *
 * <p>可以标在类上作为该控制器所有写接口的默认值，方法上的注解优先。GET 请求永远不拦截。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /**
     * 动作，建议 {@code 动词_对象} 全大写，如 {@code UPDATE_MODEL}、{@code DELETE_ACCOUNT}。
     * 留空时按方法名推导（{@code updateVisibility → UPDATE_VISIBILITY}）。
     */
    String action() default "";

    /**
     * 被操作的对象类型，如 {@code LLM_MODEL}、{@code USER_BALANCE}、{@code GATEWAY_TOKEN}。
     * 留空时按控制器类名推导（{@code LlmModelAdminController → LLM_MODEL}）。
     */
    String target() default "";

    /**
     * 业务对象 ID 的 SpEL 表达式，如 {@code #id}、{@code #request.modelIds[0]}。
     * 让「这条审计是关于哪个对象的」可以直接查到，而不必解析 after_json。
     */
    String id() default "";

    /**
     * 变更前快照的 SpEL 表达式，求值结果可以是任意对象或 {@code Map}（会被序列化成 JSON）。
     * 留空表示不记录变更前状态——纯新增操作没有「前」。
     */
    String before() default "";

    /**
     * 变更后快照的 SpEL 表达式。留空时用方法返回值（{@code #result}），
     * 返回值是 {@code void}/{@code ResponseEntity} 时可回退到 {@link #before()} 的同名表达式。
     */
    String after() default "";

    /** 未显式配置 {@link #before()} 时，是否把方法入参记进 before_json（脱敏后）。 */
    boolean recordArgs() default true;

    /** 未显式配置 {@link #after()} 时，是否把返回值记进 after_json（脱敏后）。 */
    boolean recordResult() default true;
}
