package com.hub.backend.common.ratelimit;

import java.time.Duration;

/**
 * 限流账本。
 *
 * <p>业务只依赖这个接口，具体用哪种后端由 {@code app.redis.enabled} 决定：</p>
 * <ul>
 *   <li>关闭时是进程内的 {@link InMemoryRateLimiter} —— 按实例计数，单实例部署够用；</li>
 *   <li>打开时是 {@code infra.redis.RedisRateLimiter} —— N 个副本共用一个账本，重启不清零。</li>
 * </ul>
 *
 * <p>语义（两种实现必须一致）：窗口按**每个键的第一次尝试**起算，不是按整点对齐。整点对齐看起来
 * 无害，直到窗口很短：15 秒的窗口在 :00/:15/:30/:45 各归还一次全部额度，宣称「15 秒一次」的
 * 接口实际允许 14 秒内过两次。</p>
 *
 * <p>计数是「尝试」不是「成功」，除了登录（它用 {@link #peek} 先看预算，失败才记账）。</p>
 */
public interface RateLimiter {

    /**
     * 记录一次尝试并返回是否放行。
     *
     * @param key         限流键，例如 {@code register:203.0.113.7}
     * @param maxAttempts 窗口内允许的次数
     * @param window      窗口长度
     */
    RateLimitDecision tryAcquire(String key, int maxAttempts, Duration window);

    /**
     * 一次记录 {@code cost} 个单位。
     *
     * <p>一个请求不等于一个单位：生图一次可能要四张，按一次计会让脚本用一倍窗口拿到四倍额度。
     * 被拒的批次整体回滚，因此反复用超额批次重试不会把剩余额度耗光。</p>
     */
    RateLimitDecision tryAcquire(String key, int cost, int maxAttempts, Duration window);

    /**
     * 只读探针：现在还能不能进，不计数。
     *
     * <p>「失败才计数」的限流（登录）要先判预算再决定要不要动账本，不能复用
     * {@link #tryAcquire} —— 它一调用就先记一笔，会把成功登录也算成一次尝试。</p>
     */
    RateLimitDecision peek(String key, int maxAttempts, Duration window);

    /** 清掉某个键的计数；用于运维放行与测试。 */
    void reset(String key);

    /** 清掉本服务的所有计数。仅用于测试与误判后的运维重置，别在请求路径上调用。 */
    void clear();
}
