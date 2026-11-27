package com.hub.backend.modules.auth;

import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.ratelimit.InMemoryRateLimiter;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.repository.AuthSessionRepository;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration throttling, isolated in its own context because the counter is a singleton keyed by
 * address: leaving it enabled in {@code AuthControllerTest} would make unrelated tests trip over
 * each other's quota.
 */
@SpringBootTest(properties = {
        "app.auth.bcrypt-strength=4",
        "app.rate-limit.registration.max-attempts-per-ip=2"
})
@AutoConfigureMockMvc
class RegistrationRateLimitTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

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

        // The limiter is a singleton and every MockMvc request comes from 127.0.0.1, so without a
        // reset the second test in this class would start with the quota already spent.
        rateLimiter.clear();
    }

    @Test
    void rejectsRegistrationOnceTheAddressExhaustsItsQuota() throws Exception {
        register("alice", null).andExpect(status().isOk());
        register("bob", null).andExpect(status().isOk());

        register("carol", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(ApiMessages.REGISTRATION_RATE_LIMITED)))
                .andExpect(result -> {
                    String retryAfter = result.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
                    assertTrue(retryAfter != null && Long.parseLong(retryAfter) > 0,
                            "429 must carry a positive Retry-After, was: " + retryAfter);
                    // 窗口是 15 秒的短冷却：无论请求从哪来，等待都不该超过一个窗口。
                    assertTrue(Long.parseLong(retryAfter) <= 15,
                            "Retry-After must stay within the 15s window, was: " + retryAfter);
                });
    }

    @Test
    void rotatingTheForwardedForHeaderDoesNotBypassTheLimit() throws Exception {
        // X-Forwarded-For is not trusted by default, so a script cannot reset its own quota by
        // changing the header value.
        register("alice", "203.0.113.1").andExpect(status().isOk());
        register("bob", "203.0.113.2").andExpect(status().isOk());

        register("carol", "203.0.113.3").andExpect(status().isTooManyRequests());
    }

    @Test
    void leavesLoginUnthrottled() throws Exception {
        register("alice", null).andExpect(status().isOk());
        register("bob", null).andExpect(status().isOk());
        register("carol", null).andExpect(status().isTooManyRequests());

        // the limit is scoped to registration only
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotCountRequestsRejectedByValidation() throws Exception {
        // a 400 never reaches the limiter, so it cannot burn a legitimate client's quota
        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"short"}"""))
                .andExpect(status().isBadRequest());

        register("alice", null).andExpect(status().isOk());
        register("bob", null).andExpect(status().isOk());
        register("carol", null).andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions register(String username, String forwardedFor)
            throws Exception {
        var request = post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}""".formatted(username, PASSWORD));
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(request);
    }
}
