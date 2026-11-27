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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「按客户端地址隔离」在真实 Tomcat 连接器上的验证。
 *
 * <p>MockMvc 不经过网络，{@code X-Forwarded-For} 的还原根本无从验证，所以这里用
 * {@code RANDOM_PORT} 起一个真容器：请求来自 127.0.0.1（内网段），Tomcat 的
 * {@code RemoteIpValve} 会把该头认作可信代理转发的客户端地址（{@code server.forward-headers-strategy=native}）。</p>
 *
 * <p>断言的目标只有一个：<b>不同访客不能共享同一份额度</b>。生产上前端 nginx 会把所有请求
 * 转发给后端，如果对端地址没有被还原，全站就会落在同一个限流键上 —— 这正是注册被提示
 * 「请 1515 秒后再试」的成因。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.bcrypt-strength=4",
        "app.rate-limit.registration.max-attempts-per-ip=1",
        // 把窗口拉长，避免三条请求恰好跨过 15 秒边界而让「同 IP 第二次必须被拒」偶发失败。
        "app.rate-limit.registration.window=10m"
})
class RegistrationClientIpIsolationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String PASSWORD = "correct-horse-battery";

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
    void separatesQuotaPerForwardedClientAddress() {
        // 第一个访客把额度用完。
        assertEquals(HttpStatus.OK, register("alice", "203.0.113.7").getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, register("bob", "203.0.113.7").getStatusCode());

        // 同一时刻、同一个 nginx 转发过来的另一个访客，必须还有自己的额度。
        assertEquals(HttpStatus.OK, register("carol", "203.0.113.8").getStatusCode());
    }

    private ResponseEntity<String> register(String username, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD);
        return rest.postForEntity(REGISTER_URL, new HttpEntity<>(body, headers), String.class);
    }
}
