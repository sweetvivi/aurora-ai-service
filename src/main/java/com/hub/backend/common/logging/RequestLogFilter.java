package com.hub.backend.common.logging;

import com.hub.backend.common.config.properties.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 采集一次 HTTP 请求的访问日志并落库。
 *
 * <p>放在过滤器链最外层（{@code HIGHEST_PRECEDENCE}），这样统计到的耗时包含鉴权、参数解析、
 * 序列化在内的完整开销——用户感受到的延迟就是这一段。</p>
 *
 * <p><b>三个必须绕开的坑：</b></p>
 * <ol>
 *   <li><b>请求体不能无脑缓存。</b>{@code multipart} 上限是 205MB，包一层
 *       {@code ContentCachingRequestWrapper} 等于每个上传请求在堆里多占一份全量副本，
 *       几个并发就能把内存打穿。只缓存 JSON 且长度在阈值内的请求。</li>
 *   <li><b>路由要记模板不要记原始 URI。</b>{@code /api/v1/chat/requests/8821/messages} 这种
 *       一个会话一个值，按路由聚合时基数会爆炸成几十万行。改用
 *       {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 拿到的
 *       {@code /api/v1/chat/requests/{requestId}/messages}，原始 URI 另存一列备查。</li>
 *   <li><b>响应体不缓存。</b>SSE（{@code AgentChatController}）和视频下载都以流的方式写响应，
 *       包装响应会把流吃掉或撑爆缓冲区；日志只需要状态码和耗时，没有响应体的需求。</li>
 * </ol>
 *
 * <p><b>关于 SSE 的耗时</b>：异步请求开始流式输出后，过滤器链就已经返回，这里记到的是
 * 「请求处理完成、开始推流」的耗时，而非整段对话时长。用于排查卡顿更有意义——
 * 真正慢在生成阶段的，会体现在上游调用的耗时里。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    /** 只接受这几种字符，防止把换行/控制字符写进日志（日志注入）。 */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private static final int MAX_UA_LENGTH = 512;
    private static final int MAX_URI_LENGTH = 512;
    private static final int MAX_ROUTE_LENGTH = 255;
    private static final int MAX_IP_LENGTH = 45;

    private static final Pattern REALM_VERSION =
            Pattern.compile("(Chrome|Firefox|Edg|Safari|Version)/(\\d+)");

    private final RequestLogProperties properties;
    private final RequestLogWriter writer;
    private final SecurityProperties securityProperties;

    public RequestLogFilter(RequestLogProperties properties,
                            RequestLogWriter writer,
                            SecurityProperties securityProperties) {
        this.properties = properties;
        this.writer = writer;
        this.securityProperties = securityProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        List<String> skip = properties.getSkipPaths();
        for (String prefix : skip) {
            if (prefix != null && !prefix.isBlank() && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        LocalDateTime requestTime = LocalDateTime.now();
        String traceId = resolveTraceId(request);

        RequestLogEntity entry = new RequestLogEntity(traceId, requestTime, "INFO");
        entry.setHttpMethod(request.getMethod());
        entry.setUri(truncate(request.getRequestURI(), MAX_URI_LENGTH));
        entry.setQueryString(SensitiveMasker.maskQuery(request.getQueryString(), 1024));
        entry.setClientIp(clientIp(request));

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        entry.setUserAgent(truncate(userAgent, MAX_UA_LENGTH));
        entry.setBrowser(parseBrowser(userAgent));
        entry.setOs(parseOs(userAgent));

        LogContext.begin(entry);
        // 把 trace 回给调用方：前端报错时可以带上，直接对到这条记录。
        response.setHeader(HEADER_REQUEST_ID, traceId);

        HttpServletRequest effective = wrapForBodyCaching(request);
        try {
            chain.doFilter(effective, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            // 未到达 @RestControllerAdvice 的异常才会走到这里（过滤器链自身、容器级错误）。
            // 此时响应里不会有 ApiError，就用手上的异常信息补一条。
            LogContext.recordError(ex.getClass().getSimpleName(), ex.getMessage(), null);
            throw ex;
        } finally {
            try {
                complete(entry, effective, request, response, startedAt);
            } catch (Exception ex) {
                // 采集失败绝不能影响已经完成的请求，只留一条 WARN。
                log.warn("Request log collection failed for {} {}: {}",
                        request.getMethod(), request.getRequestURI(), ex.toString());
            } finally {
                LogContext.clear();
            }
        }
    }

    private void complete(RequestLogEntity entry,
                          HttpServletRequest effective,
                          HttpServletRequest original,
                          HttpServletResponse response,
                          long startedAt) {
        int status = response.getStatus();
        // 实体用 short 对齐库里的 SMALLINT（见 RequestLogEntity 的说明）。
        entry.setResponseStatus((short) status);
        entry.setDurationMs((int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000));

        Object pattern = original.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern instanceof String s && !s.isBlank() ? s : entry.getUri();
        entry.setRoute(truncate(route, MAX_ROUTE_LENGTH));

        entry.setRequestBody(readBody(effective));

        RequestLogEntity finished = LogContext.finish();
        if (finished != null) {
            if (entry.getUserId() == null) {
                entry.setUserId(finished.getUserId());
            }
            if (entry.getSiteId() == null) {
                entry.setSiteId(finished.getSiteId());
            }
            entry.setErrorCode(finished.getErrorCode());
            entry.setErrorMessage(finished.getErrorMessage());
            entry.setErrorDetails(finished.getErrorDetails());
            entry.setUpstreamJson(finished.getUpstreamJson());
            entry.setBizType(finished.getBizType());
            entry.setBizId(finished.getBizId());
        }

        applyOutcome(entry, status);
        writer.offer(entry);
    }

    /** 依据状态码与是否发生上游故障，推导级别与结论。 */
    private void applyOutcome(RequestLogEntity entry, int status) {
        String outcome;
        if (status >= 500) {
            outcome = RequestLogEntity.OUTCOME_SERVER_ERROR;
        } else if (status >= 400) {
            outcome = RequestLogEntity.OUTCOME_CLIENT_ERROR;
        } else {
            outcome = RequestLogEntity.OUTCOME_SUCCESS;
        }
        // 上游挂掉时业务侧往往降级成 502/503，光看状态码会跟「本地 bug」混在一起。
        // 这里把它单独标出来，排查时一眼能区分「怪我们的代码」还是「怪上游」。
        String upstream = entry.getUpstreamJson();
        if (upstream != null && (upstream.contains("TIMEOUT") || upstream.contains("IO_ERROR")
                || upstream.contains("\"status\":\"5"))) {
            outcome = RequestLogEntity.OUTCOME_UPSTREAM_ERROR;
        }
        entry.setOutcome(outcome);
        entry.setLevel(switch (outcome) {
            case RequestLogEntity.OUTCOME_SUCCESS -> "INFO";
            case RequestLogEntity.OUTCOME_SERVER_ERROR, RequestLogEntity.OUTCOME_UPSTREAM_ERROR -> "ERROR";
            default -> "WARN";
        });
    }

    private HttpServletRequest wrapForBodyCaching(HttpServletRequest request) {
        if (!shouldCacheBody(request)) {
            return request;
        }
        return new ContentCachingRequestWrapper(request, properties.getMaxBodyLength());
    }

    private boolean shouldCacheBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        boolean allowed = properties.getBodyContentTypes().stream()
                .anyMatch(allowedType -> allowedType != null && lower.startsWith(allowedType.toLowerCase()));
        if (!allowed) {
            return false;
        }
        // 长度未知（分块传输）或超过阈值时都不缓存：未知长度可能是任意大。
        long length = request.getContentLengthLong();
        return length > 0 && length <= properties.getMaxBodyLength();
    }

    /**
     * 读取已缓存的请求体。只有 Controller 真正读过 {@code InputStream} 时缓存才有内容，
     * 因此 GET 或未消费 body 的请求会返回 {@code null}，属预期。
     */
    private String readBody(HttpServletRequest request) {
        if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
            return null;
        }
        byte[] cached = wrapper.getContentAsByteArray();
        if (cached.length == 0) {
            return null;
        }
        return SensitiveMasker.maskJson(new String(cached, StandardCharsets.UTF_8),
                properties.getMaxBodyLength());
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_REQUEST_ID);
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 取客户端地址。
     *
     * <p>判断逻辑与 {@code ClientContextResolver} 保持一致，但不直接复用：那个类在
     * {@code modules.auth} 下，{@code common} 反向依赖模块层会把包结构搞乱。</p>
     *
     * <p>默认取 socket 对端地址。容器侧已配置 {@code server.forward-headers-strategy: native}，
     * Tomcat 的 {@code RemoteIpValve} 会在受信代理场景下把 {@code remoteAddr} 改写成真实客户端，
     * 所以这里拿到的通常已经是真实 IP，且不可被客户端伪造。</p>
     */
    private String clientIp(HttpServletRequest request) {
        String ip = null;
        if (securityProperties.isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].strip();
                if (!first.isEmpty()) {
                    ip = first;
                }
            }
        }
        if (ip == null) {
            ip = request.getRemoteAddr();
        }
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        return truncate(ip, MAX_IP_LENGTH);
    }

    /** 只提取浏览器名与主版本号；完整 UA 已单独存列，这里要的是可聚合的维度。 */
    static String parseBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        Matcher matcher = REALM_VERSION.matcher(userAgent);
        if (matcher.find()) {
            String name = switch (matcher.group(1)) {
                case "Edg" -> "Edge";
                case "Version" -> userAgent.contains("Safari") ? "Safari" : "Other";
                default -> matcher.group(1);
            };
            return name + " " + matcher.group(2);
        }
        // 微信/支付宝等内置浏览器不带标准版本串，单独识别，否则全部落到 Other 里没有区分度。
        if (userAgent.contains("MicroMessenger")) {
            return "WeChat";
        }
        if (userAgent.contains("AlipayClient")) {
            return "Alipay";
        }
        return "Other";
    }

    static String parseOs(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        if (userAgent.contains("Windows NT 10")) {
            // NT 10.0 同时对应 Win10 和 Win11，UA 里无法区分，合并为 Windows 10+。
            return "Windows 10+";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Other";
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
