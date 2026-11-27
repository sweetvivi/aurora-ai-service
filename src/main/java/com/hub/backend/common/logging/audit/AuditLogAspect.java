package com.hub.backend.common.logging.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.hub.backend.common.config.properties.SecurityProperties;
import com.hub.backend.common.logging.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@code @AuditLog} 的执行者：取快照 → 放行业务 → 算差异 → 异步落库 + 打一条结构化日志。
 *
 * <p><b>为什么切在 Controller 而不是 Service</b>：审计要回答「哪个接口、谁调的、成功了没有」，
 * 这些信息只在 Controller 这一层齐备。而且管理端 Service 普遍带 {@code @Transactional}，
 * 切在 Service 里等于在事务内部写审计——事务回滚了，审计却已经落地，记录与实际状态不符。
 * 切在 Controller 外层的效果是：业务事务已提交，audit 才落库，两边一致。</p>
 *
 * <p><b>为什么 before 快照必须在 {@code proceed()} 之前取</b>：余额、状态这类字段执行完就被覆盖了，
 * 事后再查只能查到新值，「改前是多少」永久丢失。这是审计与普通业务日志的根本差别。</p>
 *
 * <p><b>为什么不吞异常也要记</b>：业务抛异常时同样写一条 FAILURE 记录，然后把原异常抛回去。
 * 被拒绝的删除、校验失败的批量操作，恰恰是需要审计的部分（有人尝试过）。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /** SpEL 编译缓存：同一接口每分钟可能被调几百次，反复解析表达式是纯浪费。 */
    private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    /** 只作为上下文使用、不适合进快照的参数类型。 */
    private static final String[] NON_PAYLOAD_PARAMS = {"HttpServletRequest", "HttpServletResponse"};

    private final AuditLogProperties properties;
    private final AuditSnapshots snapshots;
    private final AuditLogWriter writer;
    private final SecurityProperties securityProperties;
    private final BeanResolver beanResolver;

    public AuditLogAspect(AuditLogProperties properties,
                          AuditSnapshots snapshots,
                          AuditLogWriter writer,
                          SecurityProperties securityProperties,
                          ApplicationContext applicationContext) {
        this.properties = properties;
        this.snapshots = snapshots;
        this.writer = writer;
        this.securityProperties = securityProperties;
        // 让注解里能引用任意 Spring bean（如 @auditProbes.balance(...)），
        // 否则「余额变更前后值」只能靠业务代码自己塞进来。
        this.beanResolver = new org.springframework.context.expression.BeanFactoryResolver(applicationContext);
    }

    @Around("@annotation(com.hub.backend.common.logging.audit.AuditLog)"
            + " || @within(com.hub.backend.common.logging.audit.AuditLog)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        AuditLog annotation = resolve(method);
        if (annotation == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = currentRequest();
        // GET/HEAD/OPTIONS 只读，审计没有意义。类级注解为了方便会把整个控制器纳进来，
        // 这个判断就是「类级注解」与「只审写操作」之间的那道闸。
        if (request != null && isSafeMethod(request.getMethod())) {
            return joinPoint.proceed();
        }

        long start = System.nanoTime();
        StandardEvaluationContext context = buildContext(joinPoint, signature, null);
        JsonNode before = captureBefore(annotation, context);
        String beforeJson = snapshots.toJson(before, properties.getMaxSnapshotLength());

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable error) {
            record(annotation, method, joinPoint, request, before, beforeJson, null, null,
                    AuditLogEntity.OUTCOME_FAILURE, error, start);
            throw error;
        }

        JsonNode after;
        String afterJson;
        try {
            context = buildContext(joinPoint, signature, result);
            after = captureAfter(annotation, context, result);
            afterJson = snapshots.toJson(after, properties.getMaxSnapshotLength());
        } catch (RuntimeException ex) {
            // 快照失败不影响业务结果，记一条 WARN 继续写审计（只有一边也比没有强）。
            log.warn("Audit snapshot failed for {}.{}: {}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), ex.toString());
            after = null;
            afterJson = null;
        }
        record(annotation, method, joinPoint, request, before, beforeJson, after, afterJson,
                AuditLogEntity.OUTCOME_SUCCESS, null, start);
        return result;
    }

    /** 方法上的注解优先，没有则退到类上的默认值。 */
    private AuditLog resolve(Method method) {
        AuditLog onMethod = AnnotatedElementUtils.findMergedAnnotation(method, AuditLog.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), AuditLog.class);
    }

    private void record(AuditLog annotation,
                        Method method,
                        ProceedingJoinPoint joinPoint,
                        HttpServletRequest request,
                        JsonNode before,
                        String beforeJson,
                        JsonNode after,
                        String afterJson,
                        String outcome,
                        Throwable error,
                        long startNanos) {
        try {
            AuditLogEntity entry = new AuditLogEntity();
            entry.setOccurredAt(LocalDateTime.now());
            entry.setTraceId(MDC.get(LogContext.MDC_TRACE));
            entry.setAction(resolveAction(annotation, method));
            entry.setTarget(resolveTarget(annotation, method));
            entry.setBeforeJson(beforeJson);
            entry.setAfterJson(afterJson);
            entry.setChangedJson(snapshots.diff(before, after, properties.getMaxSnapshotLength()));
            entry.setOutcome(outcome);
            entry.setErrorMessage(error == null ? null
                    : AuditSnapshots.truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 1024));
            entry.setDurationMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
            entry.setCreatedAt(LocalDateTime.now());
            fillActor(entry, request);
            entry.setTargetId(resolveTargetId(annotation, joinPoint, method, request));

            // 结构化日志：与库里的记录一一对应，但落在 backend.log 里。
            // 作用是「实时」——出事当时就能 grep 到，不必等有人去查表。
            log.info("audit action={} target={} targetId={} actor={}/{} outcome={} ms={} changed={}",
                    entry.getAction(), entry.getTarget(), entry.getTargetId(),
                    entry.getActorType(), entry.getActorId(), entry.getOutcome(), entry.getDurationMs(),
                    AuditSnapshots.truncate(entry.getChangedJson(), 512));
            writer.offer(entry);
        } catch (RuntimeException ex) {
            // 审计本身出错绝不能影响业务：业务已经成功，返 500 才是真正的事故。
            log.warn("Audit record failed for {}.{}: {}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), ex.toString());
        }
    }

    private JsonNode captureBefore(AuditLog annotation, StandardEvaluationContext context) {
        if (!annotation.before().isEmpty()) {
            return snapshots.toMaskedTree(evaluate(annotation.before(), context));
        }
        if (!annotation.recordArgs()) {
            return null;
        }
        return snapshots.toMaskedTree(context.lookupVariable("argsSnapshot"));
    }

    private JsonNode captureAfter(AuditLog annotation, StandardEvaluationContext context, Object result) {
        if (!annotation.after().isEmpty()) {
            return snapshots.toMaskedTree(evaluate(annotation.after(), context));
        }
        if (!annotation.recordResult() || result == null) {
            return null;
        }
        return snapshots.toMaskedTree(result);
    }

    private Object evaluate(String expression, StandardEvaluationContext context) {
        Expression parsed = EXPRESSION_CACHE.computeIfAbsent(expression, PARSER::parseExpression);
        return parsed.getValue(context);
    }

    /**
     * 构造 SpEL 上下文：既支持 {@code #id} 这样的具名参数，也支持 {@code #p0} / {@code #a0}
     * 这类位置参数（参数名在编译时丢失时不至于整条审计失效）。
     */
    private StandardEvaluationContext buildContext(ProceedingJoinPoint joinPoint,
                                                   MethodSignature signature,
                                                   Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(beanResolver);
        Object[] args = joinPoint.getArgs();
        String[] names = signature.getParameterNames();
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            context.setVariable("p" + i, arg);
            context.setVariable("a" + i, arg);
            if (names != null && i < names.length && names[i] != null) {
                context.setVariable(names[i], arg);
            }
            if (isPayload(arg)) {
                String key = (names != null && i < names.length && names[i] != null) ? names[i] : "p" + i;
                payload.put(key, arg);
            }
        }
        // 入参快照：只保留真正的业务载荷，ServletRequest 这类不进 JSON（会序列化出一堆容器内部字段）。
        context.setVariable("argsSnapshot", payload);
        context.setVariable("result", result);
        return context;
    }

    private static boolean isPayload(Object arg) {
        if (arg == null) {
            return false;
        }
        String type = arg.getClass().getName();
        for (String excluded : NON_PAYLOAD_PARAMS) {
            if (type.contains(excluded)) {
                return false;
            }
        }
        // MultipartFile / InputStream 之类不能转 JSON，转了也只会得到一串内部状态。
        return !(arg instanceof HttpServletRequest) && !(arg instanceof HttpServletResponse);
    }

    private String resolveTargetId(AuditLog annotation, ProceedingJoinPoint joinPoint,
                                   Method method, HttpServletRequest request) {
        if (!annotation.id().isEmpty()) {
            try {
                StandardEvaluationContext context = buildContext(joinPoint,
                        (MethodSignature) joinPoint.getSignature(), null);
                Object value = evaluate(annotation.id(), context);
                return value == null ? null : AuditSnapshots.truncate(String.valueOf(value), 64);
            } catch (RuntimeException ex) {
                log.debug("Audit id expression failed: {} ({})", annotation.id(), ex.toString());
            }
        }
        return null;
    }

    /**
     * 操作人。管理端目前用固定密钥鉴权（{@code AdminGuard}），没有操作人账号，
     * 所以只能记到「管理密钥 + 来源 IP」这一级；将来接入管理端账号体系时，
     * 只要在 {@code AdminGuard} 之后把操作人写进 MDC（{@link LogContext#MDC_UID}），这里自动升级。
     */
    private void fillActor(AuditLogEntity entry, HttpServletRequest request) {
        String uid = MDC.get(LogContext.MDC_UID);
        if (uid != null && !uid.isBlank()) {
            entry.setActorType(AuditLogEntity.ACTOR_USER);
            entry.setActorId(uid);
        } else if (request != null) {
            entry.setActorType(AuditLogEntity.ACTOR_ADMIN);
            // 不存密钥本身：它是全站最高权限凭证，进日志等于二次泄露。
            entry.setActorId("admin-key");
        } else {
            entry.setActorType(AuditLogEntity.ACTOR_SYSTEM);
            entry.setActorId("scheduler");
        }
        if (request == null) {
            return;
        }
        entry.setClientIp(clientIp(request));
        entry.setUserAgent(AuditSnapshots.truncate(request.getHeader("User-Agent"), 512));
        entry.setHttpMethod(request.getMethod());
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        entry.setRoute(pattern == null ? request.getRequestURI() : String.valueOf(pattern));
    }

    /** 与 {@code RequestLogFilter} 同一套取值规则：默认取 socket 对端，受信代理时才看 XFF。 */
    private String clientIp(HttpServletRequest request) {
        if (securityProperties.isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].strip();
                if (!first.isEmpty()) {
                    return AuditSnapshots.truncate(first, 64);
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : AuditSnapshots.truncate(remote, 64);
    }

    private static String resolveAction(AuditLog annotation, Method method) {
        if (annotation != null && !annotation.action().isEmpty()) {
            return annotation.action();
        }
        return toUpperSnake(method.getName());
    }

    /** {@code LlmModelAdminController → LLM_MODEL}：去掉 Controller 后缀再转下划线。 */
    static String resolveTarget(AuditLog annotation, Method method) {
        if (annotation != null && !annotation.target().isEmpty()) {
            return annotation.target();
        }
        String name = method.getDeclaringClass().getSimpleName();
        name = name.replaceAll("(Admin)?Controller$", "");
        return toUpperSnake(name);
    }

    static String toUpperSnake(String camel) {
        return CAMEL_BOUNDARY.matcher(camel).replaceAll("$1_$2").toUpperCase(Locale.ROOT);
    }

    private static boolean isSafeMethod(String httpMethod) {
        return "GET".equalsIgnoreCase(httpMethod)
                || "HEAD".equalsIgnoreCase(httpMethod)
                || "OPTIONS".equalsIgnoreCase(httpMethod);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
