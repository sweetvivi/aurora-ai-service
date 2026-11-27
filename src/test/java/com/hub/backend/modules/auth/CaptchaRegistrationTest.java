package com.hub.backend.modules.auth;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration with the captcha switched on.
 *
 * <p>Kept in its own context because the rest of the suite runs with {@code app.captcha.enabled=false}
 * (a test cannot read a random image). The answer is obtained from {@link CaptchaService} directly
 * instead of the response body, which is also a small proof that the image endpoint never leaks it.</p>
 */
@SpringBootTest(properties = {
        "app.auth.bcrypt-strength=4",
        "app.rate-limit.enabled=false",
        "app.captcha.enabled=true"
})
@AutoConfigureMockMvc
class CaptchaRegistrationTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String CAPTCHA_URL = "/api/v1/auth/captcha";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @BeforeEach
    void seedSite() {
        authSessionRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();
        appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
    }

    @Test
    void servesAChallengeWithoutEverReturningTheAnswer() throws Exception {
        mockMvc.perform(get(CAPTCHA_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captchaId").isNotEmpty())
                .andExpect(jsonPath("$.image").value(startsWith("data:image/png;base64,")))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void reportsTheCaptchaAsRequiredWhenItIsEnabled() throws Exception {
        // 前端用同一个接口决定注册表单要渲染哪些字段，验证码开关必须与 GET /captcha 一致。
        mockMvc.perform(get("/api/v1/auth/registration-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captchaRequired").value(true));
    }

    @Test
    void rejectsRegistrationWithoutACaptcha() throws Exception {
        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("alice", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先完成图形验证码"));
    }

    @Test
    void rejectsAWrongAnswer() throws Exception {
        CaptchaService.Challenge challenge = captchaService.create("127.0.0.1");
        String wrong = challenge.code().equals("AAAA") ? "BBBB" : "AAAA";

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("alice", challenge.id(), wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("验证码不正确或已过期")));
    }

    @Test
    void registersWhenTheAnswerMatches() throws Exception {
        CaptchaService.Challenge challenge = captchaService.create("127.0.0.1");

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("alice", challenge.id(), challenge.code())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void aUsedChallengeCannotRegisterASecondAccount() throws Exception {
        CaptchaService.Challenge challenge = captchaService.create("127.0.0.1");

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("alice", challenge.id(), challenge.code())))
                .andExpect(status().isOk());

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("bob", challenge.id(), challenge.code())))
                .andExpect(status().isBadRequest());
    }

    private static String body(String username, String captchaId, String captchaCode) {
        if (captchaId == null) {
            return """
                    {"username":"%s","password":"%s"}""".formatted(username, PASSWORD);
        }
        return """
                {"username":"%s","password":"%s","captchaId":"%s","captchaCode":"%s"}"""
                .formatted(username, PASSWORD, captchaId, captchaCode);
    }
}
