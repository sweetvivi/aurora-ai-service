package com.hub.backend.modules.chat;

import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.coordination.ChatCancelBus;
import com.hub.backend.common.coordination.ChatRequestStateStore;
import com.hub.backend.common.coordination.NoopChatCancelBus;
import com.hub.backend.common.coordination.NoopChatRequestStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks in-flight chat generations so a client can stop one.
 *
 * <p>Entries are keyed by account <em>and</em> request id. Keying on the request id alone would let
 * one account squat another account's id and have its own generation rejected; scoping by account
 * makes the ownership check structural rather than an extra condition someone can forget.</p>
 *
 * <p>Terminal entries are retained for a short grace period so that stopping an already finished
 * generation is idempotent instead of a confusing 404 — the common case being a user clicking stop
 * just as the answer completes.</p>
 *
 * <p><b>多实例</b>：取消标志必须翻在持有那条 SSE 连接的进程上。本地找不到条目时，注册表先查
 * {@link ChatRequestStateStore} 确认这条请求是否还活着，再通过 {@link ChatCancelBus} 广播，
 * 让持有者自己把标志翻掉 —— 否则「停止」打到别的副本上就只能返回 404。单实例部署时这两个
 * 端口是空实现，行为与以前完全一致。</p>
 */
@Component
public class ChatRequestRegistry {

    /** The generation finished normally. */
    public static final String OUTCOME_COMPLETED = "COMPLETED";

    /** The generation was stopped before it completed. */
    public static final String OUTCOME_CANCELLED = "CANCELLED";

    /** The generation failed upstream. */
    public static final String OUTCOME_FAILED = "FAILED";

    /** Result of a cancellation attempt. */
    public enum CancelOutcome {
        /** The generation was running and has now been asked to stop. */
        CANCELLED,
        /** The generation had already finished; the request is retained but nothing was running. */
        ALREADY_FINISHED,
        /** This account has no such request. */
        NOT_FOUND
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration retention;
    private final int maxTrackedRequests;
    private final ChatRequestStateStore stateStore;
    private final ChatCancelBus cancelBus;
    /** 进行中状态的存活时间：必须覆盖一次流式生成的最长耗时，否则跨实例取消会查不到。 */
    private final Duration activeTtl;
    /** 终态多留一会儿，让「刚结束就点停止」在任何实例上都还能拿到 204。 */
    private final Duration terminalTtl;

    @Autowired
    public ChatRequestRegistry(Clock clock,
                               ChatProperties chatProperties,
                               ChatRequestStateStore stateStore,
                               ChatCancelBus cancelBus) {
        this.clock = clock;
        this.retention = chatProperties.getRequestRetention();
        this.maxTrackedRequests = Math.max(1, chatProperties.getMaxTrackedRequests());
        this.stateStore = stateStore;
        this.cancelBus = cancelBus;
        this.activeTtl = chatProperties.getStreamTimeout().plusMinutes(5);
        this.terminalTtl = this.retention.plusMinutes(5);
        this.cancelBus.addListener(this::onRemoteCancel);
    }

    /** 单实例 / 测试用：没有共享账本，也没有别的实例要通知。 */
    public ChatRequestRegistry(Clock clock, ChatProperties chatProperties) {
        this(clock, chatProperties, new NoopChatRequestStateStore(), new NoopChatCancelBus());
    }

    public String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public Entry register(Long userId, String requestId) {
        Entry entry = new Entry(requestId, userId, clock.instant());
        String key = key(userId, requestId);
        entries.put(key, entry);
        // 先登记到共享账本，再清理本地：另一个实例随后到来的取消才查得到这条请求。
        stateStore.markActive(key, activeTtl);
        // Evict after inserting, so the capacity check sees the entry that just arrived.
        evict();
        return entry;
    }

    public CancelOutcome cancel(Long userId, String requestId) {
        String key = key(userId, requestId);
        Entry entry = entries.get(key);
        if (entry != null && !entry.isEvictable(clock.instant(), retention)) {
            if (entry.isTerminal()) {
                return CancelOutcome.ALREADY_FINISHED;
            }
            if (!entry.requestCancel()) {
                return CancelOutcome.ALREADY_FINISHED;
            }
            // 顺手把共享状态也改掉：别的实例再点一次停止时，应当得到 204 而不是再广播一遍。
            stateStore.markCancelled(key, terminalTtl);
            return CancelOutcome.CANCELLED;
        }

        // 本地没有这条请求：可能跑在别的实例上。共享账本说它还在跑，就广播让持有者停。
        Optional<String> state = stateStore.state(key);
        if (state.isEmpty()) {
            return CancelOutcome.NOT_FOUND;
        }
        if (!ChatRequestStateStore.ACTIVE.equals(state.get())) {
            return CancelOutcome.ALREADY_FINISHED;
        }
        if (!stateStore.markCancelled(key, terminalTtl)) {
            // 另一个取消请求刚刚抢到了这次翻转。
            return CancelOutcome.ALREADY_FINISHED;
        }
        cancelBus.publish(key);
        return CancelOutcome.CANCELLED;
    }

    /**
     * Marks the generation terminal so later cancellations become idempotent.
     *
     * @param outcome one of {@link #OUTCOME_COMPLETED}, {@link #OUTCOME_CANCELLED}, {@link #OUTCOME_FAILED}
     */
    public void finish(Long userId, String requestId, String outcome) {
        String key = key(userId, requestId);
        Entry entry = entries.get(key);
        if (entry != null) {
            entry.finish(outcome, clock.instant());
        }
        // 终态写进共享账本：之后任何实例上的取消都应得到 204，而不是 404。
        stateStore.markTerminal(key, outcome, terminalTtl);
    }

    /**
     * 收到别的实例广播的取消：只有真正持有这条生成的实例会命中本地条目。
     *
     * <p>在 Redis 监听线程上执行，因此这里只翻一个原子标志，不做任何 IO。</p>
     */
    private void onRemoteCancel(String requestKey) {
        Entry entry = entries.get(requestKey);
        if (entry != null) {
            entry.requestCancel();
        }
    }

    /** @return number of tracked entries, for tests and diagnostics */
    public int trackedRequests() {
        return entries.size();
    }

    private void evict() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> entry.getValue().isEvictable(now, retention));
        if (entries.size() <= maxTrackedRequests) {
            return;
        }
        // Still over the cap: nothing here is individually evictable, so drop the oldest finished
        // entries. Bounded memory matters more than keeping a stale id cancellable.
        //
        // Only terminal entries are dropped. An active one is never discarded: that would silently
        // make a running generation unstoppable. In practice active entries are bounded by the
        // streaming executor's capacity anyway, so the leftovers being trimmed are the finished ids
        // waiting out their retention window.
        int excess = entries.size() - maxTrackedRequests;
        entries.values().stream()
                .filter(Entry::isTerminal)
                .sorted(Comparator.comparing(Entry::startedAt))
                .limit(excess)
                .map(entry -> key(entry.userId(), entry.requestId()))
                .forEach(entries::remove);
    }

    private static String key(Long userId, String requestId) {
        return userId + ":" + requestId;
    }

    /**
     * Handle of one in-flight generation. The streaming loop reads {@link #isCancelled()} for every
     * chunk; the cancel endpoint flips it from another thread.
     */
    public static final class Entry {

        private final String requestId;
        private final Long userId;
        private final Instant startedAt;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String outcome;
        private volatile Instant finishedAt;

        private Entry(String requestId, Long userId, Instant startedAt) {
            this.requestId = requestId;
            this.userId = userId;
            this.startedAt = startedAt;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isTerminal() {
            return outcome != null;
        }

        public String outcome() {
            return outcome;
        }

        public String requestId() {
            return requestId;
        }

        public Long userId() {
            return userId;
        }

        Instant startedAt() {
            return startedAt;
        }

        /** @return whether this call was the one that flipped the flag, {@code false} if already stopped */
        private boolean requestCancel() {
            return cancelled.compareAndSet(false, true);
        }

        private void finish(String outcome, Instant at) {
            if (this.outcome == null) {
                this.outcome = outcome;
                this.finishedAt = at;
            }
        }

        private boolean isEvictable(Instant now, Duration retention) {
            return isTerminal() && finishedAt != null && finishedAt.plus(retention).isBefore(now);
        }
    }
}
