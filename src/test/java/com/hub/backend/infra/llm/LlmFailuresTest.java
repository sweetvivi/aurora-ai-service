package com.hub.backend.infra.llm;

import com.hub.backend.common.exception.LlmInvocationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试判定的验收标准。
 *
 * <p>两类失败必须分得开：瞬时抖动重试一次往往就好了（不重试等于让用户重跑整篇），
 * 而余额不足重试三次只会把同一个错误重复三遍、让用户多等十几秒。</p>
 */
class LlmFailuresTest {

    private static LlmInvocationException error(String message) {
        return new LlmInvocationException(message);
    }

    @Test
    void retriesTransientGatewayFailures() {
        assertTrue(LlmFailures.isRetryable(error("Model gateway stream failed: Connection reset")));
        assertTrue(LlmFailures.isRetryable(error("Model gateway returned HTTP 502: bad gateway")));
        assertTrue(LlmFailures.isRetryable(error("Model gateway returned HTTP 503")));
        assertTrue(LlmFailures.isRetryable(error("模型服务当前限流（HTTP 429），请等十几秒后重试")));
        assertTrue(LlmFailures.isRetryable(error("model returned an empty response")));
        assertTrue(LlmFailures.isRetryable(error(
                "Model gateway returned no content over the streaming endpoint either")));
        assertTrue(LlmFailures.isRetryable(error("Model gateway call failed: timeout")));
    }

    @Test
    void neverRetriesQuotaFailures() {
        String quota = "模型账户余额不足，本次请求未能执行（HTTP 403）。"
                + "当前剩余 $0.240750，本次需要预冻结 $0.402900。"
                + "预冻额度按单次请求的最大输出量计算，请求结束后会按实际用量退回。";
        assertFalse(LlmFailures.isRetryable(error(quota)));
        assertFalse(LlmFailures.isRetryable(error("insufficient_user_quota")));
        assertFalse(LlmFailures.isRetryable(error("预扣费额度失败，用户剩余额度：$0.1")));
    }

    @Test
    void accountLevelRateLimitIsRetriedEvenThoughTheMessageMentionsNoQuota() {
        // New API 把「所有账号都被限流」原样透传成 429；这句话里没有 quota，
        // 不能因为同一条消息里可能带余额字眼就被判成致命失败。
        assertTrue(LlmFailures.isRetryable(error(
                "All available accounts are currently rate-limited. Please retry later. (HTTP 429)")));
        assertTrue(LlmFailures.isRetryable(error(
                "模型服务当前限流（HTTP 429），请等十几秒后重试；连续失败可换一个模型。")));
    }

    @Test
    void neverRetriesDeterministicRejections() {
        assertFalse(LlmFailures.isRetryable(error("模型服务的密钥无效或已过期（HTTP 401）。")));
        assertFalse(LlmFailures.isRetryable(error("当前没有可用的通道来运行这个模型：可能是该模型已下线")));
        assertFalse(LlmFailures.isRetryable(error("Model gateway returned HTTP 400: bad request")));
        assertFalse(LlmFailures.isRetryable(error("Model gateway call was interrupted")));
    }

    @Test
    void unknownFailuresAreNotRetried() {
        assertFalse(LlmFailures.isRetryable(error("something nobody has seen before")));
        assertFalse(LlmFailures.isRetryable(null));
    }
}
