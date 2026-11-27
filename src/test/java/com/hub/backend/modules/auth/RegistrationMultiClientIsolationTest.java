package com.hub.backend.modules.auth;

import com.hub.backend.common.ratelimit.InMemoryRateLimiter;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.repository.AuthSessionRepository;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用「多个来源地址」模拟多访客，验证限流是每访客一份，而不是全站共用一份。
 *
 * <p>生产上前端 nginx 会把访客地址写进 {@code X-Forwarded-For}，这里用同一个头模拟不同访客；
 * 请求来自 127.0.0.1，属于受信任代理网段，Tomcat 会把该头还原成对端地址（见
 * {@code server.forward-headers-strategy=native}）。</p>
 *
 * <p>贯穿的两个断言：</p>
 * <ul>
 *   <li>任何一个地址用满额度，其它地址的第一条请求仍然放行 —— 限流不会「扩大到全站」；</li>
 *   <li>被拒的等待时间不超过一个窗口（这里是 15 秒），不存在小时级封禁。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.bcrypt-strength=4",
        "app.rate-limit.registration.max-attempts-per-ip=1",
        "app.rate-limit.registration.window=15s"
})
class RegistrationMultiClientIsolationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String PASSWORD = "correct-horse-battery";
    private static final int WINDOW_SECONDS = 15;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void seedSite() {
        authSessionRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();
        appSiteRepository.save(new AppSiteEntity("default", "Default Site"));

        rateLimiter.clear();
    }

    @Test
    void tenVisitorsEachGetOneAttemptAndNobodyElseIsBlocked() {
        awaitFreshWindow();

        // 十个不同访客各注册一次：全站没有总次数上限，一条都不该被拒。
        for (int i = 0; i < 10; i++) {
            assertEquals(HttpStatus.OK, register("many" + i, address(i)).getStatusCode(),
                    "第 " + i + " 个访客的首条请求不应被限流");
        }

        // 同一窗口内，每个访客第二次都会撞自己的额度，且等待不超过 15 秒。
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> second = register("again" + i, address(i));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getStatusCode(),
                    "第 " + i + " 个访客的第二次请求应被拒");
            assertRetryWithinWindow(second);
        }

        // 再冒出一个新访客，此刻仍然能注册 —— 十个访客的占用没有堆积成全站限制。
        assertEquals(HttpStatus.OK, register("latecomer", "198.51.100.240").getStatusCode());
    }

    @Test
    void oneAbusiveAddressCannotLockTheWholeSite() {
        awaitFreshWindow();

        // 一个地址连打 6 次：只有第一条放行，其余全部 429。
        assertEquals(HttpStatus.OK, register("flood0", "203.0.113.10").getStatusCode());
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<String> rejected = register("flood" + i, "203.0.113.10");
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
            assertRetryWithinWindow(rejected);
        }

        // 与此同时，另外两个地址的首次请求都正常放行。
        assertEquals(HttpStatus.OK, register("neighbour1", "203.0.113.11").getStatusCode());
        assertEquals(HttpStatus.OK, register("neighbour2", "203.0.113.12").getStatusCode());
    }

    @Test
    void theBlockExpiresAfterOneWindow() {
        awaitFreshWindow();

        assertEquals(HttpStatus.OK, register("cycle1", "203.0.113.20").getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, register("cycle2", "203.0.113.20").getStatusCode());

        // 等过一个窗口，同一个地址就能继续用，不需要等整点、也不会延长时间。
        awaitFreshWindow();
        assertEquals(HttpStatus.OK, register("cycle3", "203.0.113.20").getStatusCode());
    }

    private ResponseEntity<String> register(String username, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD);
        return rest.postForEntity(REGISTER_URL, new HttpEntity<>(body, headers), String.class);
    }

    private static void assertRetryWithinWindow(ResponseEntity<String> response) {
        String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertTrue(retryAfter != null, "429 必须带 Retry-After");
        assertTrue(Long.parseLong(retryAfter) <= WINDOW_SECONDS,
                "等待时间不该超过一个窗口，实际 " + retryAfter + " 秒");
    }

    /**
     * 计数器按 15 秒对齐，跨过边界会让额度提前恢复。等一个全新的窗口再发请求，
     * 断言才不会偶发失败。
     */
    private static void awaitFreshWindow() {
        long windowMillis = WINDOW_SECONDS * 1000L;
        long now = System.currentTimeMillis();
        long wait = windowMillis - (now % windowMillis);
        if (wait < 500) {
            wait += windowMillis;
        }
        LockSupport.parkNanos(Duration.ofMillis(wait).toNanos());
    }

    private static String address(int index) {
        return "198.51.100." + (index + 1);
    }
}
