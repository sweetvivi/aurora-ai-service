package com.hub.backend.infra.llm;

import com.hub.backend.common.exception.LlmInvocationException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判断一次模型调用失败「值不值得再试一次」。
 *
 * <p><b>为什么需要它。</b>中继网关的失败分两类，处理方式完全相反：</p>
 *
 * <ul>
 *   <li><b>瞬时</b>：429 限流、5xx、连接被重置、上游把流式转非流式时把正文丢了。这类重试一次
 *       通常就好了 —— 不重试的话，一章失败会让整篇重来，前面写好的章节白花钱；</li>
 *   <li><b>确定性</b>：余额不足、密钥无效、模型下线、参数被拒。重试只是把同样的错误再等一遍，
 *       用户更需要的是立刻看到「该充值 / 该换模型」。</li>
 * </ul>
 *
 * <p>先看 HTTP 状态码（最能说明问题），再看关键字；都认不出时判为不可重试 ——
 * 宁可少发一次请求，也不要让用户对着一个大概率不会成功的调用反复等待。</p>
 */
public final class LlmFailures {

    /** 上游错误消息里带的 HTTP 状态码：`HTTP 502`、`（HTTP 429）` 都认。 */
    private static final Pattern HTTP_STATUS = Pattern.compile("http\\s*(\\d{3})", Pattern.CASE_INSENSITIVE);

    /** 与状态码无关的确定性失败。 */
    private static final String[] FATAL_MARKERS = {
            // 「quota」不能单独认：New API 的 429 原文是 "All available accounts are currently
            // rate-limited"，那是限流；把 quota 当致命标记会把 429 判成不可重试，
            // 与下面「429 可重试」直接打架 —— 谁先命中谁决定，结果是一次都不重试。
            "余额不足", "insufficient_user_quota", "insufficient quota", "insufficient_quota",
            "quota exhausted",
            "密钥", "invalid api key", "invalid_api_key",
            "没有可用的通道", "已下线", "model_not_found", "模型不存在",
    };

    /** 与状态码无关的瞬时失败。 */
    private static final String[] TRANSIENT_MARKERS = {
            "限流", "rate limit", "rate_limit",
            "调用失败", "流式失败", "gateway call failed", "gateway stream failed",
            "no content", "empty response", "返回了空内容",
            "超时", "timed out", "timeout",
            "connection reset", "connection refused", "econnreset", "econnrefused",
    };

    private LlmFailures() {
    }

    public static boolean isRetryable(LlmInvocationException error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();

        for (String marker : FATAL_MARKERS) {
            if (message.contains(marker)) {
                return false;
            }
        }
        // 余额不足的另一种说法：「预扣费额度失败，用户剩余额度：$0.1」。不能用「quota」泛匹配，
        // 那会把 429 的 "rate-limited" 也吞进来。
        if (message.contains("预扣") || message.contains("剩余额度")) {
            return false;
        }
        // 线程被中断是服务关闭或用户取消，重试没有意义
        if (message.contains("interrupted")) {
            return false;
        }

        Matcher status = HTTP_STATUS.matcher(message);
        while (status.find()) {
            int code = Integer.parseInt(status.group(1));
            if (code == 429 || code >= 500) {
                return true;
            }
            if (code >= 400) {
                // 400 参数错、401/403 鉴权或计费、404 模型不存在：再试也一样
                return false;
            }
        }

        for (String marker : TRANSIENT_MARKERS) {
            if (message.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
