package com.hub.backend.infra.redis;

import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.coordination.ChatCancelBus;
import com.hub.backend.common.coordination.ChatRequestStateStore;
import com.hub.backend.modules.chat.ChatRequestRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 跨实例取消：B 实例收到停止请求，A 实例上的生成真的停下来。
 *
 * <p>用两个 {@link ChatRequestRegistry} 模拟两个进程：它们各自只有本地 map，共享同一份 Redis
 * 状态与同一条广播总线 —— 正是多副本部署的真实拓扑。需要本地 Redis，没有就跳过。</p>
 */
@SpringBootTest(properties = "app.redis.enabled=true")
class RedisChatCoordinationTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;
    private static final long OWNER = 7L;

    @Autowired
    private ChatRequestStateStore stateStore;

    @Autowired
    private ChatCancelBus cancelBus;

    @Autowired
    private ChatProperties chatProperties;

    @Autowired
    private StringRedisTemplate redis;

    private final List<String> createdKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(), "Redis is not running on " + HOST + ":" + PORT + "; skipping");
    }

    @AfterEach
    void tearDown() {
        if (!createdKeys.isEmpty()) {
            redis.delete(createdKeys);
            createdKeys.clear();
        }
    }

    @Test
    void stateMovesFromActiveToCancelledExactlyOnce() {
        String key = key("state");

        stateStore.markActive(key, Duration.ofMinutes(2));
        assertThat(stateStore.state(key)).contains(ChatRequestStateStore.ACTIVE);

        assertThat(stateStore.markCancelled(key, Duration.ofMinutes(2))).isTrue();
        assertThat(stateStore.state(key)).contains("CANCELLED");
        // 第二次翻转必须失败：调用方据此返回 204 而不是再广播一遍。
        assertThat(stateStore.markCancelled(key, Duration.ofMinutes(2))).isFalse();
    }

    @Test
    void theBusDeliversASignalToEverySubscriber() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        cancelBus.addListener(signal -> {
            received.add(signal);
            latch.countDown();
        });

        String signal = key("bus");
        cancelBus.publish(signal);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly(signal);
    }

    @Test
    void aCancelArrivingOnAnotherInstanceStopsTheOwner() {
        ChatRequestRegistry owner = new ChatRequestRegistry(
                Clock.systemUTC(), chatProperties, stateStore, cancelBus);
        ChatRequestRegistry other = new ChatRequestRegistry(
                Clock.systemUTC(), chatProperties, stateStore, cancelBus);

        String key = key("cross");
        ChatRequestRegistry.Entry running = owner.register(OWNER, key);

        ChatRequestRegistry.CancelOutcome outcome = other.cancel(OWNER, key);

        assertThat(outcome).isEqualTo(ChatRequestRegistry.CancelOutcome.CANCELLED);
        await(() -> running.isCancelled());
        // 再点一次停止：状态已经是 CANCELLED，应该 204 而不是再广播。
        assertThat(other.cancel(OWNER, key))
                .isEqualTo(ChatRequestRegistry.CancelOutcome.ALREADY_FINISHED);
    }

    @Test
    void anUnknownRequestIsStillNotFound() {
        ChatRequestRegistry other = new ChatRequestRegistry(
                Clock.systemUTC(), chatProperties, stateStore, cancelBus);

        assertThat(other.cancel(OWNER, key("missing")))
                .isEqualTo(ChatRequestRegistry.CancelOutcome.NOT_FOUND);
    }

    @Test
    void cancellingAfterFinishIsIdempotentNotAnError() {
        ChatRequestRegistry owner = new ChatRequestRegistry(
                Clock.systemUTC(), chatProperties, stateStore, cancelBus);
        ChatRequestRegistry other = new ChatRequestRegistry(
                Clock.systemUTC(), chatProperties, stateStore, cancelBus);

        String key = key("finished");
        owner.register(OWNER, key);
        owner.finish(OWNER, key, ChatRequestRegistry.OUTCOME_COMPLETED);

        assertThat(other.cancel(OWNER, key))
                .isEqualTo(ChatRequestRegistry.CancelOutcome.ALREADY_FINISHED);
    }

    private String key(String suffix) {
        String requestId = "req-" + suffix + "-" + System.nanoTime();
        String redisKey = RedisChatRequestStateStore.KEY_PREFIX + OWNER + ":" + requestId;
        createdKeys.add(redisKey);
        return OWNER + ":" + requestId;
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition not met within 5s");
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 300);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
