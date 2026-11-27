package com.hub.backend.modules.redemption;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.repository.AuthSessionRepository;
import com.hub.backend.modules.auth.repository.UserIdentityRepository;
import com.hub.backend.modules.gateway.repository.UserGatewayTokenRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract of {@code POST /api/v1/redemptions/redeem} when the session password is gone.
 *
 * <p>The vault is emptied here through {@link SessionPasswordVault#forget(Long)}, which is exactly
 * what a process restart does to it. Both tests stop before any New API call: the missing password
 * is rejected first, so nothing external is needed.</p>
 */
@SpringBootTest(properties = {
        "app.auth.bcrypt-strength=4",
        "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class UserRedemptionControllerTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String REDEEM_URL = "/api/v1/redemptions/redeem";
    private static final String ME_URL = "/api/v1/auth/me";
    private static final String SESSION_PASSWORD_URL = "/api/v1/auth/session/password";
    private static final String USERNAME = "redeemer";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String CODE = "ABCD1234EFGH5678";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private UserGatewayTokenRepository userGatewayTokenRepository;

    @Autowired
    private SessionPasswordVault passwordVault;

    private Long userId;

    @BeforeEach
    void seedAccount() throws Exception {
        userGatewayTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();
        appSiteRepository.save(new AppSiteEntity("default", "Default Site")).getId();

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk());

        UserAccountEntity account = userAccountRepository
                .findFirstBySiteIdAndUsername(
                        appSiteRepository.findAll().get(0).getId(), USERNAME)
                .orElseThrow();
        userId = account.getId();
    }

    @Test
    void asksForThePasswordAndKeepsTheUserSignedIn() throws Exception {
        String token = signIn();
        passwordVault.forget(userId);

        mockMvc.perform(post(REDEEM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(null)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.status").value(428))
                .andExpect(jsonPath("$.message").value(
                        containsString("确认账号密码")));

        // The point of 428 instead of 401: the token is still good, so nothing signs the user out.
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAWrongPasswordWithoutLeakingWhetherTheCodeIsValid() throws Exception {
        String token = signIn();
        passwordVault.forget(userId);

        mockMvc.perform(post(REDEEM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody("not-the-password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("当前密码不正确"));
    }

    @Test
    void confirmingThePasswordRestoresTheSessionSoARetryNoLongerAnswers428() throws Exception {
        String token = signIn();
        passwordVault.forget(userId);

        mockMvc.perform(post(REDEEM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(null)))
                .andExpect(status().isPreconditionRequired());

        mockMvc.perform(post(SESSION_PASSWORD_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD)))
                .andExpect(status().isNoContent());

        // The vault holds the password again, which is exactly what the 428 was asking for.
        assertTrue(passwordVault.password(userId).isPresent());
    }

    @Test
    void aWrongConfirmationIsRejectedWithFourHundred() throws Exception {
        String token = signIn();

        mockMvc.perform(post(SESSION_PASSWORD_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("not-the-password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前密码不正确"));
    }

    @Test
    void anAnonymousCallerCannotConfirmAPassword() throws Exception {
        mockMvc.perform(post(SESSION_PASSWORD_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    private String signIn() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","clientType":"web"}
                                """.formatted(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode parsed = objectMapper.readTree(body);
        return parsed.get("accessToken").asText();
    }

    private String registerBody() {
        return """
                {"username":"%s","password":"%s","nickname":"%s"}
                """.formatted(USERNAME, PASSWORD, USERNAME);
    }

    private String redeemBody(String password) {
        if (password == null) {
            return """
                    {"code":"%s"}
                    """.formatted(CODE);
        }
        return """
                {"code":"%s","password":"%s"}
                """.formatted(CODE, password);
    }

    private String passwordBody(String password) {
        return """
                {"password":"%s"}
                """.formatted(password);
    }
}
