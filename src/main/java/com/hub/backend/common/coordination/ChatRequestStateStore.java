package com.hub.backend.common.coordination;

import java.time.Duration;
import java.util.Optional;

/**
 * 进行中的对话生成在哪、状态如何。
 *
 * <p>单实例时这张表就在内存里（{@code NoopChatRequestStateStore} 什么也不做，注册表只看本地
 * 那张 map）；多实例时换成 Redis，于是「B 实例收到取消请求、生成其实跑在 A 实例上」才有可能
 * 被正确回答：B 能查到这条请求存在且仍 ACTIVE，而不是直接 404。</p>
 *
 * <p>状态取值：{@code ACTIVE}，或终态 {@code COMPLETED} / {@code CANCELLED} / {@code FAILED}
 * （与 {@code ChatRequestRegistry.OUTCOME_*} 一致）。</p>
 */
public interface ChatRequestStateStore {

    /** 生成正在进行。终态直接复用 {@code ChatRequestRegistry.OUTCOME_*} 的取值。 */
    String ACTIVE = "ACTIVE";

    /** 记录一条刚开始的生成。 */
    void markActive(String requestKey, Duration ttl);

    /** 记录终态，用于让「生成刚结束就点停止」返回 204 而不是 404。 */
    void markTerminal(String requestKey, String outcome, Duration ttl);

    /** @return 状态；查不到（从未有过、或已过期、或没开 Redis）时为空 */
    Optional<String> state(String requestKey);

    /**
     * 把 {@code ACTIVE} 改成 {@code CANCELLED}。
     *
     * @return 本次调用是否真的翻转了状态；已经是终态时返回 {@code false}，调用方据此返回 204
     *         而不是重复广播
     */
    boolean markCancelled(String requestKey, Duration fallbackTtl);
}
