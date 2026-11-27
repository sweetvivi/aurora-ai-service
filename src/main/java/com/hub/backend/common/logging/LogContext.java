package com.hub.backend.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前请求的日志上下文，贯穿「采集 → 补充 → 落库」全过程。
 *
 * <p>存在的原因：一次请求的信息分散在三层，且产生的顺序是反的——</p>
 * <ul>
 *   <li>过滤器最先执行，但那时还不知道是谁（认证发生在 Controller 里）；</li>
 *   <li>用户身份要到 {@code CurrentUserResolver} 才确定；</li>
 *   <li>错误信息要到 {@code GlobalExceptionHandler} 或 Filter 的 catch 里才有；</li>
 *   <li>上游调用散落在 14 个 client 里，且一次请求可能调好几次。</li>
 * </ul>
 * <p>所以需要一个请求级容器，让后发生的各方把结果写进来，最后由过滤器统一收口。</p>
 *
 * <p><b>为什么 ThreadLocal 而不是参数传递</b>：要传的话，得给 568 个文件里的方法签名动手术。
 * 代价是它只对同一线程有效——异步线程（线程池、@Async、CompletableFuture）里
 * {@link #current()} 会返回 {@code null}，所有方法都做了空值兼容，退化为「只记主线程那一段」，
 * 不会抛异常影响业务。</p>
 *
 * <p>MDC 与 ThreadLocal 并存不是重复：MDC 给 slf4j 的日志文本用（{@code %X{traceId}}），
 * 让现有的 {@code log.info/warn/error} 自动带上上下文；ThreadLocal 给结构化落库用。</p>
 */
public final class LogContext {

    public static final String MDC_TRACE = "traceId";
    public static final String MDC_UID = "uid";
    public static final String MDC_SITE = "siteId";

    /** 单请求上游调用次数上限。正常不会超过 3 次，超过的是重试风暴，只留前 20 条足够定位。 */
    private static final int MAX_UPSTREAM_CALLS = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private LogContext() {
    }

    /** 一次请求内累积的可变状态。 */
    private static final class State {
        private final RequestLogEntity log;
        private final List<UpstreamCall> upstreamCalls = new ArrayList<>(4);
        private Map<String, String> errorDetails;
        private boolean errorRecorded;

        private State(RequestLogEntity log) {
            this.log = log;
        }
    }

    /** 一次上游调用的摘要。 */
    public record UpstreamCall(String name, String status, long durationMs) {
    }

    /** 由过滤器在请求进入时调用。重复调用（如内部 forward）以最后一次为准。 */
    public static void begin(RequestLogEntity log) {
        CURRENT.set(new State(log));
        if (log.getTraceId() != null) {
            MDC.put(MDC_TRACE, log.getTraceId());
        }
    }

    /**
     * 记录当前账号。由 {@code CurrentUserResolver} 在解析成功后调用，
     * 这是全站用户维度的唯一写入点。
     */
    public static void setUser(Long userId, Long siteId) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        state.log.setUserId(userId);
        state.log.setSiteId(siteId);
        if (userId != null) {
            MDC.put(MDC_UID, String.valueOf(userId));
        }
        if (siteId != null) {
            MDC.put(MDC_SITE, String.valueOf(siteId));
        }
    }

    /** 记录业务对象，便于「从一条日志直接跳到对应的会话/订单/任务」。 */
    public static void setBiz(String type, Object id) {
        State state = CURRENT.get();
        if (state == null || id == null) {
            return;
        }
        state.log.setBizType(type);
        String text = String.valueOf(id);
        state.log.setBizId(text.length() > 64 ? text.substring(0, 64) : text);
    }

    /**
     * 记录一次上游调用。{@code status} 用字符串是为了容纳 {@code TIMEOUT} / {@code IO_ERROR}
     * 这类非 HTTP 结果——它们在排查时的信息量比一个 500 大得多。
     */
    public static void addUpstream(String name, String status, long durationMs) {
        State state = CURRENT.get();
        if (state == null || state.upstreamCalls.size() >= MAX_UPSTREAM_CALLS) {
            return;
        }
        state.upstreamCalls.add(new UpstreamCall(name, status, durationMs));
    }

    /**
     * 记录返回给调用方的错误信息。由 {@code GlobalExceptionHandler} 在构造 {@code ApiError}
     * 之后调用——那里是全站错误响应的唯一出口，记录的文案与用户实际看到的完全一致。
     *
     * <p>只保留第一次：一个请求只会返回一个错误体，后续调用是重载链路的重复写入。</p>
     */
    public static void recordError(String code, String message, Map<String, String> details) {
        State state = CURRENT.get();
        if (state == null || state.errorRecorded) {
            return;
        }
        state.errorRecorded = true;
        state.log.setErrorCode(truncate(code, 64));
        state.log.setErrorMessage(truncate(message, 1024));
        if (details != null && !details.isEmpty()) {
            Map<String, String> masked = new LinkedHashMap<>();
            details.forEach((k, v) -> masked.put(k, SensitiveMasker.isDeniedField(k)
                    ? "***" : SensitiveMasker.maskText(truncate(v, 256))));
            state.errorDetails = masked;
        }
    }

    /** 供过滤器在出站时读取当前状态；没有活动请求时返回 {@code null}。 */
    static RequestLogEntity currentLog() {
        State state = CURRENT.get();
        return state == null ? null : state.log;
    }

    /**
     * 收口：把累积的上游调用与错误明细写进实体，返回可落库的对象。
     * 同时清理上下文——线程是复用的，不清理会把上一个请求的数据带到下一个。
     */
    static RequestLogEntity finish() {
        State state = CURRENT.get();
        if (state == null) {
            return null;
        }
        RequestLogEntity log = state.log;
        if (!state.upstreamCalls.isEmpty()) {
            try {
                log.setUpstreamJson(MAPPER.writeValueAsString(state.upstreamCalls));
            } catch (Exception ex) {
                // 上游摘要序列化失败不该影响这条日志落库，丢掉该字段即可。
                log.setUpstreamJson(null);
            }
        }
        if (state.errorDetails != null) {
            try {
                log.setErrorDetails(MAPPER.writeValueAsString(state.errorDetails));
            } catch (Exception ex) {
                log.setErrorDetails(null);
            }
        }
        clear();
        return log;
    }

    /** 清理 ThreadLocal 与 MDC。必须在 finally 里调用，否则线程池复用会造成串数据。 */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_TRACE);
        MDC.remove(MDC_UID);
        MDC.remove(MDC_SITE);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
