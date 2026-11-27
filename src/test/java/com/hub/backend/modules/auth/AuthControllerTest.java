package com.hub.backend.modules.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.auth.entity.AuthSessionEntity;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.auth.bcrypt-strength=4",
        // Registration throttling has its own context in RegistrationRateLimitTest; leaving it on
        // here would let unrelated tests exhaust the shared per-address quota.
        "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String ME_URL = "/api/v1/auth/me";
    private static final String PROFILE_URL = "/api/v1/auth/profile";
    private static final String AVATAR_URL = "/api/v1/auth/avatar";
    private static final String PASSWORD_URL = "/api/v1/auth/password";
    private static final String ACCOUNT_URL = "/api/v1/auth/account";

    private static final String USERNAME = "alice";
    private static final String PASSWORD = "correct-horse-battery";
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Path UPLOAD_ROOT = createTempRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local-root", UPLOAD_ROOT::toString);
    }

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

    private Long siteId;

    @BeforeEach
    void seedSite() {
        userGatewayTokenRepository.deleteAll();
        authSessionRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        siteId = appSiteRepository.save(new AppSiteEntity("default", "Default Site")).getId();
    }

    // ------------------------------------------------------------------ register

    @Test
    void reportsTheRegistrationFormCapabilities() throws Exception {
        /*
         * 前端靠这个接口决定要不要渲染邮箱输入框：没有它，表单只能在用户点「获取验证码」失败一次
         * 之后才知道上游没开邮箱验证。本测试上下文没有 New API 集成（
         * app.auth.new-api-registration-enabled=false），所以两项都必须是 false，且不能去连上游。
         */
        mockMvc.perform(get("/api/v1/auth/registration-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.emailCodeAvailable").value(false));
    }

    @Test
    void registersAnAccountAndSignsItIn() throws Exception {
        JsonNode body = register(USERNAME, PASSWORD);

        assertEquals("Bearer", body.get("tokenType").asText());
        assertTrue(body.get("accessToken").asText().split("\\.").length == 3);
        assertTrue(body.get("refreshToken").asText().length() >= 40);
        assertEquals(1800L, body.get("expiresIn").asLong());
        assertEquals(USERNAME, body.get("user").get("username").asText());
        assertEquals(USERNAME, body.get("user").get("nickname").asText());
        assertEquals(siteId, body.get("user").get("siteId").asLong());
        assertEquals("ACTIVE", body.get("user").get("status").asText());

        // the response never carries anything password related
        assertTrue(body.get("user").get("passwordHash") == null);
        assertTrue(body.toString().indexOf(PASSWORD) < 0);

        UserAccountEntity stored = userAccountRepository.findFirstBySiteIdAndUsername(siteId, USERNAME)
                .orElseThrow();
        assertNotNull(stored.getPasswordHash());
        assertTrue(stored.getPasswordHash().startsWith("$argon2id$"), stored.getPasswordHash());
        assertNotNull(stored.getLastLoginAt());

        // the identity row is namespaced by site so the global unique key stays meaningful
        assertTrue(userIdentityRepository
                .existsByProviderAndProviderSubject("password", "default:" + USERNAME));
    }

    @Test
    void rejectsADuplicateUsername() throws Exception {
        register(USERNAME, PASSWORD);

        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(USERNAME, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已被占用")));
    }

    @Test
    void rejectsAShortPassword() throws Exception {
        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(USERNAME, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("密码长度需为 8–128 个字符")));
    }

    @Test
    void rejectsAUsernameWithUnsupportedCharacters() throws Exception {
        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("al ice!", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.username").exists());
    }

    // ------------------------------------------------------------------ login

    @Test
    void signsInWithValidCredentials() throws Exception {
        register(USERNAME, PASSWORD);

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(USERNAME));
    }

    @Test
    void answersAWrongPasswordAndAnUnknownAccountIdentically() throws Exception {
        register(USERNAME, PASSWORD);

        String wrongPassword = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownAccount = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // no account enumeration through the message
        assertEquals(objectMapper.readTree(wrongPassword).get("message").asText(),
                objectMapper.readTree(unknownAccount).get("message").asText());
    }

    @Test
    void refusesADisabledAccount() throws Exception {
        register(USERNAME, PASSWORD);
        UserAccountEntity account = userAccountRepository
                .findFirstBySiteIdAndUsername(siteId, USERNAME).orElseThrow();
        setStatus(account, "DISABLED");
        userAccountRepository.save(account);

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ me

    @Test
    void requiresAuthenticationForTheCurrentAccount() throws Exception {
        mockMvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void rejectsAGarbageAccessToken() throws Exception {
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheAccountBehindTheAccessToken() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();

        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.siteId").value(siteId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    // ------------------------------------------------------------------ profile

    @Test
    void updatesNicknameAndBindsContactChannels() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();

        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(" Alice Chen ", "Alice@Example.com", "13800138000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Alice Chen"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.phone").value("13800138000"));

        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Alice Chen"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.phone").value("13800138000"));
    }

    @Test
    void clearsContactBindingsWhenTheyAreBlank() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();
        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Alice", "alice@example.com", "13800138000")))
                .andExpect(status().isOk());

        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Alice", "", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());

        UserAccountEntity stored = userAccountRepository
                .findFirstBySiteIdAndUsername(siteId, USERNAME).orElseThrow();
        assertNull(stored.getEmail());
        assertNull(stored.getPhone());
    }

    @Test
    void rejectsContactChannelsAlreadyBoundToAnotherAccount() throws Exception {
        String aliceToken = register(USERNAME, PASSWORD).get("accessToken").asText();
        String bobToken = register("bob", PASSWORD).get("accessToken").asText();

        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Alice", "shared@example.com", "13800138000")))
                .andExpect(status().isOk());

        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Bob", "shared@example.com", "13900139000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("该邮箱已绑定其他账号"));

        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody("Bob", "bob@example.com", "13800138000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("该手机号已绑定其他账号"));
    }

    @Test
    void rejectsInvalidProfileFields() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();

        mockMvc.perform(put(PROFILE_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(" ", "not-an-email", "123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.nickname").exists())
                .andExpect(jsonPath("$.details.email").exists())
                .andExpect(jsonPath("$.details.phone").exists());
    }

    // ------------------------------------------------------------------ avatar

    @Test
    void uploadsAnAvatarAndExposesItOnTheAccount() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();
        MockMultipartFile avatar = new MockMultipartFile(
                "file", "avatar.png", "image/png", PNG_SIGNATURE);

        mockMvc.perform(multipart(AVATAR_URL).file(avatar)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").exists())
                .andExpect(jsonPath("$.username").value(USERNAME));

        UserAccountEntity stored = userAccountRepository
                .findFirstBySiteIdAndUsername(siteId, USERNAME).orElseThrow();
        assertNotNull(stored.getAvatarUrl());
        assertTrue(stored.getAvatarUrl().startsWith("/api/v1/files/"));
        assertTrue(stored.getAvatarUrl().endsWith("/content"));
    }

    @Test
    void rejectsNonImageAvatars() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();
        MockMultipartFile text = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart(AVATAR_URL).file(text)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ refresh

    @Test
    void rotatesTheRefreshToken() throws Exception {
        JsonNode session = register(USERNAME, PASSWORD);
        String refreshToken = session.get("refreshToken").asText();

        String rotated = mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(USERNAME))
                .andReturn().getResponse().getContentAsString();

        String newAccessToken = objectMapper.readTree(rotated).get("accessToken").asText();

        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccessToken))
                .andExpect(status().isOk());

        // the presented session was revoked, the new one is active
        assertEquals(2, authSessionRepository.count());
        assertEquals(1, countActiveSessions());
    }

    @Test
    void treatsReuseOfARotatedRefreshTokenAsTheft() throws Exception {
        JsonNode first = register(USERNAME, PASSWORD);
        JsonNode second = signIn(USERNAME, PASSWORD);

        JsonNode rotated = rotate(first.get("refreshToken").asText());

        // replaying the already rotated token must fail and drop every active session
        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(first.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized());

        assertEquals(0, countActiveSessions());

        // both other sessions are now dead, including the one the rotation had just created
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + rotated.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + second.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnUnknownRefreshToken() throws Exception {
        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("totally-unknown-token")))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ logout

    @Test
    void logoutRevokesTheSessionImmediately() throws Exception {
        JsonNode session = register(USERNAME, PASSWORD);
        String accessToken = session.get("accessToken").asText();
        String refreshToken = session.get("refreshToken").asText();

        mockMvc.perform(post(LOGOUT_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // the access token is still cryptographically valid, but its session is gone
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRequiresAToken() throws Exception {
        mockMvc.perform(post(LOGOUT_URL))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ password

    @Test
    void changesThePasswordAndRevokesOtherSessions() throws Exception {
        JsonNode current = register(USERNAME, PASSWORD);
        JsonNode other = signIn(USERNAME, PASSWORD);
        String currentAccessToken = current.get("accessToken").asText();
        String newPassword = "new-correct-horse-battery";

        mockMvc.perform(put(PASSWORD_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(PASSWORD, newPassword)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ME_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentAccessToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(ME_URL)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + other.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, PASSWORD)))
                .andExpect(status().isUnauthorized());
        signIn(USERNAME, newPassword);
    }

    @Test
    void rejectsPasswordChangeWithTheWrongCurrentPassword() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();

        mockMvc.perform(put(PASSWORD_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("not-the-password", "new-password-123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("当前密码不正确"));
    }

    @Test
    void rejectsAnOverlongPassword() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();

        // 129 code points: one past the ceiling. Argon2id has no 72-byte truncation, so the limit
        // is the explicit one in PasswordPolicy rather than a hash function's input buffer.
        mockMvc.perform(put(PASSWORD_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(PASSWORD, "a".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("新密码长度需为 8–128 个字符")));
    }

    // ------------------------------------------------------------------ account deletion

    @Test
    void deletesTheAccountByDisablingAndAnonymisingIt() throws Exception {
        JsonNode first = register(USERNAME, PASSWORD);
        JsonNode second = signIn(USERNAME, PASSWORD);
        Long userId = first.get("user").get("id").asLong();

        mockMvc.perform(delete(ACCOUNT_URL)
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + first.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteAccountBody(PASSWORD, "注销账号")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + first.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(ME_URL).header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + second.get("accessToken").asText()))
                .andExpect(status().isUnauthorized());

        assertTrue(userAccountRepository.findById(userId).isEmpty(),
                "注销后账号行应被物理删除");
        assertTrue(userIdentityRepository.findAll().isEmpty());
        assertEquals(0, countActiveSessions());

        // The original login name and identity can be registered again after deletion.
        register(USERNAME, PASSWORD);
    }

    @Test
    void rejectsAccountDeletionWithWrongConfirmationOrPassword() throws Exception {
        String accessToken = register(USERNAME, PASSWORD).get("accessToken").asText();

        mockMvc.perform(delete(ACCOUNT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteAccountBody(PASSWORD, "注销")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "请输入「注销账号」以确认"));

        mockMvc.perform(delete(ACCOUNT_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteAccountBody("not-the-password", "注销账号")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("当前密码不正确"));

        UserAccountEntity account = userAccountRepository
                .findFirstBySiteIdAndUsername(siteId, USERNAME).orElseThrow();
        assertEquals("ACTIVE", account.getStatus());
    }

    // ------------------------------------------------------------------ contract

    @Test
    void publishesAuthPathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/profile'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/avatar'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/account'].delete").exists())
                .andExpect(jsonPath("$.components.schemas.AuthToken").exists())
                .andExpect(jsonPath("$.components.schemas.AuthUser").exists())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest").exists())
                .andExpect(jsonPath("$.components.schemas.ChangePasswordRequest").exists())
                .andExpect(jsonPath("$.components.schemas.DeleteAccountRequest").exists());
    }

    // ------------------------------------------------------------------ helpers

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("auth-avatar-uploads-");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create temp upload root", exception);
        }
    }

    private JsonNode register(String username, String password) throws Exception {
        String body = mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode signIn(String username, String password) throws Exception {
        String body = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode rotate(String refreshToken) throws Exception {
        String body = mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static String registerBody(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    private static String loginBody(String username, String password) {
        return """
                {"username":"%s","password":"%s","clientType":"web"}""".formatted(username, password);
    }

    private static String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}""".formatted(refreshToken);
    }

    private static String profileBody(String nickname, String email, String phone) {
        return """
                {"nickname":"%s","email":"%s","phone":"%s"}"""
                .formatted(nickname, email, phone);
    }

    private static String changePasswordBody(String currentPassword, String newPassword) {
        return """
                {"currentPassword":"%s","newPassword":"%s"}"""
                .formatted(currentPassword, newPassword);
    }

    private static String deleteAccountBody(String password, String confirmation) {
        return """
                {"password":"%s","confirmation":"%s"}"""
                .formatted(password, confirmation);
    }

    private long countActiveSessions() {
        LocalDateTime now = LocalDateTime.now();
        return authSessionRepository.findAll().stream()
                .filter(session -> session.isActive(now))
                .count();
    }

    private static void setStatus(UserAccountEntity account, String status) {
        try {
            var field = UserAccountEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(account, status);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
