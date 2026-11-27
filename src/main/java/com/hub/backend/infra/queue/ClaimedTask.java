package com.hub.backend.infra.queue;

/**
 * 已被某个 worker 领取的任务。
 *
 * <p>{@code leaseOwner} 随领取结果一起返回，后续 ack / nack / 归还都必须带上它：租约过期后
 * 同一个任务可能已经被别的实例领走，拿着旧 owner 去收尾应该命中 0 行，而不是替别人写状态。</p>
 */
public record ClaimedTask(long id,
                          String queueName,
                          String payload,
                          int attempts,
                          int maxAttempts,
                          String leaseOwner) {
}
