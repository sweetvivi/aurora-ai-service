package com.hub.backend.modules.gateway.deletion;

import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.modules.gateway.crypto.AesGcmTokenCipher;
import com.hub.backend.modules.gateway.entity.NewApiDeletionTaskEntity;
import com.hub.backend.modules.gateway.repository.NewApiDeletionTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注销被 New API 限流之后，任务必须真的能在限流窗口过去之后被删掉。
 *
 * <p>这几条用例串起整条链路：入队时的字段是否够重放（管理员路径只需 id，自助路径需要
 * 用户名 + 密码，而密码必须是密文）、扫描是否按退避排期、以及重试成功/被拒之后行去了哪里。
 * 「等一会儿」用直接改库里的 {@code next_attempt_at} 模拟，而不是把时钟拨快 —— 后者要替换
 * 全局 {@code Clock}，会影响同一上下文里所有按时间工作的组件。</p>
 */
@SpringBootTest(properties = {
        // 与 user_gateway_tokens 同一把 AES-GCM 密钥；自助路径的密码要靠它落库。
        "app.new-api.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.new-api.enabled=true",
        "app.new-api.deletion-retry-enabled=true",
        // 自动扫描推到一小时后：这个类自己调 sweep()，不让后台线程在中途插进来。
        "app.new-api.deletion-sweep-interval-millis=3600000"
})
class NewApiAccountDeletionRetryTest {

    private static final long USER_ID = 501L;
    private static final long NEW_API_USER_ID = 9001L;
    private static final String USERNAME = "queued-user";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String LOGIN_TOKEN = "login-token";

    @Autowired
    private NewApiAccountDeletionQueue queue;

    @Autowired
    private NewApiAccountDeletionWorker worker;

    @Autowired
    private NewApiDeletionTaskRepository tasks;

    @Autowired
    private AesGcmTokenCipher cipher;

    @MockitoBean
    private NewApiManagementClient newApi;

    @BeforeEach
    void clearQueue() {
        tasks.deleteAll();
    }

    @Test
    void administratorTaskKeepsOnlyTheIdItNeedsToReplay() {
        assertTrue(queue.enqueue(USER_ID, NEW_API_USER_ID, USERNAME, PASSWORD,
                NewApiDeletionTaskEntity.MODE_ADMIN, "HTTP 429"));

        NewApiDeletionTaskEntity task = tasks.findFirstByLocalUserId(USER_ID).orElseThrow();
        assertEquals(NewApiDeletionTaskEntity.STATUS_PENDING, task.getStatus());
        assertEquals(NewApiDeletionTaskEntity.MODE_ADMIN, task.getMode());
        assertEquals(NEW_API_USER_ID, task.getNewapiUserId());
        assertEquals(0, task.getAttempts());
        assertFalse(task.hasSealedPassword(),
                "管理员路径只需要一个 id，没有理由再多存一份密码");
    }

    @Test
    void selfServiceTaskKeepsThePasswordSealed() {
        assertTrue(queue.enqueue(USER_ID, null, USERNAME, PASSWORD,
                NewApiDeletionTaskEntity.MODE_SELF, "HTTP 429"));

        NewApiDeletionTaskEntity task = tasks.findFirstByLocalUserId(USER_ID).orElseThrow();
        assertTrue(task.hasSealedPassword());
        // 重放要能拿回明文，但库里不能出现明文。
        assertEquals(PASSWORD, queue.passwordOf(task));
        assertFalse(new String(task.getPasswordCiphertext(), StandardCharsets.UTF_8)
                .contains(PASSWORD));
    }

    @Test
    void queuesOneTaskPerUser() {
        assertTrue(queue.enqueue(USER_ID, NEW_API_USER_ID, USERNAME, null,
                NewApiDeletionTaskEntity.MODE_ADMIN, "HTTP 429"));
        assertTrue(queue.enqueue(USER_ID, NEW_API_USER_ID, USERNAME, null,
                NewApiDeletionTaskEntity.MODE_ADMIN, "HTTP 429"));

        assertEquals(1, tasks.count());
    }

    @Test
    void sweepRemovesTheRowThroughTheAdministratorEndpoint() {
        seed(NewApiDeletionTaskEntity.MODE_ADMIN, NEW_API_USER_ID);

        worker.sweep();

        verify(newApi).hardDeleteUser(NEW_API_USER_ID);
        assertEquals(0, tasks.count(), "删成了就该整行消失，不留历史");
    }

    @Test
    void aRateLimitedRetryStaysQueuedForLater() {
        seed(NewApiDeletionTaskEntity.MODE_SELF, null);
        when(newApi.login(USERNAME, PASSWORD))
                .thenThrow(new NewApiHttpException(429, "rate limited"));

        worker.sweep();

        NewApiDeletionTaskEntity task = tasks.findFirstByLocalUserId(USER_ID).orElseThrow();
        assertEquals(NewApiDeletionTaskEntity.STATUS_PENDING, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertNotNull(task.getLastError());
        // 60 秒后第一次重试：限流窗口是 20 分钟，立刻重试只会再吃一个 429。
        assertTrue(task.getNextAttemptAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)),
                "a rate limit must push the next attempt into the future, not retry at once");
    }

    @Test
    void theNextSweepAfterTheBackoffFinishesTheJob() {
        seed(NewApiDeletionTaskEntity.MODE_SELF, null);
        when(newApi.login(USERNAME, PASSWORD))
                .thenThrow(new NewApiHttpException(429, "rate limited"))
                .thenReturn(new NewApiLoginSession(NEW_API_USER_ID, USERNAME, LOGIN_TOKEN));

        worker.sweep();
        forceDue();

        worker.sweep();

        verify(newApi).deleteUser(LOGIN_TOKEN, PASSWORD);
        assertEquals(0, tasks.count());
    }

    @Test
    void aRefusalIsNotRetriedForever() {
        seed(NewApiDeletionTaskEntity.MODE_SELF, null);
        when(newApi.login(USERNAME, PASSWORD))
                .thenThrow(new NewApiHttpException(401, "new-api-user: incorrect password"));

        worker.sweep();

        NewApiDeletionTaskEntity task = tasks.findFirstByLocalUserId(USER_ID).orElseThrow();
        assertEquals(NewApiDeletionTaskEntity.STATUS_FAILED, task.getStatus());
        // 行留着：这是「密码被改了」这类要人看一眼的情况，删掉行等于把线索也删了。
        assertEquals(1, tasks.count());
    }

    private void seed(String mode, Long newapiUserId) {
        NewApiDeletionTaskEntity task = new NewApiDeletionTaskEntity(
                USER_ID, newapiUserId, USERNAME, mode,
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        if (NewApiDeletionTaskEntity.MODE_SELF.equals(mode)) {
            AesGcmTokenCipher.EncryptedToken sealed = cipher.encrypt(PASSWORD);
            task.sealPassword(sealed.ciphertext(), sealed.nonce(), sealed.tag());
        }
        tasks.save(task);
    }

    /** 把排期整个拉到过去，代替「等了几分钟」。 */
    private void forceDue() {
        NewApiDeletionTaskEntity task = tasks.findFirstByLocalUserId(USER_ID).orElseThrow();
        task.reschedule(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), "forced by the test");
        tasks.save(task);
    }
}
