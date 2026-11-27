package com.hub.backend.infra.newapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client for New API endpoints used by one authenticated user or by the anonymous catalogue.
 *
 * <p>It intentionally has no global management-token path. Models and prices come from
 * {@code /api/pricing}, anonymously or — when the plaza requires a login — with the optional
 * read-only sync account; PAT and relay operations use only credentials owned by the current
 * user.</p>
 */
@Component
public class NewApiManagementClient {

    private static final Logger log = LoggerFactory.getLogger(NewApiManagementClient.class);

    private static final String CONTENT_TYPE = "application/json";
    private static final String PRICING_PATH = "/api/pricing";
    private static final String PASSWORD_V2_LABEL = "password-v2";
    private static final int AES_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ERROR_LENGTH = 256;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final NewApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Last answer of {@code GET /api/status} about email verification.
     *
     * <p>Registration and sign in both need it, so it is cached for a minute: an upstream setting
     * cannot change faster than that anyway, and an unreachable upstream must not turn every sign
     * in into an extra timeout. A failed refresh keeps the previous value rather than flipping the
     * site to "no email required".</p>
     */
    private volatile boolean emailVerificationEnabled;
    private volatile Instant emailVerificationCheckedAt = Instant.EPOCH;
    private static final Duration EMAIL_VERIFICATION_CACHE_TTL = Duration.ofMinutes(1);

    /**
     * Session token of {@link NewApiProperties#getCatalogUsername()}, cached until New API rejects
     * it.
     *
     * <p>Signing in on every sync would spend a login on a 5-minute timer, and New API rate-limits
     * sign-in; a rejected token (password rotated, session evicted) clears this so the next call
     * signs in again instead of failing forever. Kept in memory only: it is a short-lived
     * credential and restarting re-obtains it.</p>
     */
    private volatile String catalogSessionToken;

    /** 目录会话的过期时刻（epoch 秒，0 = 站点没给），用于过期前主动续而不是等 401。 */
    private volatile long catalogSessionExpiresAt;

    /** 目录账号登录失败后的退避窗口与原因，避免每个同步周期都去撞一个已经打满的会话上限。 */
    private volatile long catalogLoginBlockedUntil;
    private volatile int catalogLoginFailures;
    private volatile String catalogLoginLastError;

    @Autowired
    public NewApiManagementClient(NewApiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    NewApiManagementClient(NewApiProperties properties,
                           ObjectMapper objectMapper,
                           HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public NewApiLoginSession login(String username, String password) {
        JsonNode response;
        try {
            response = send(
                    "POST",
                    "/api/user/login",
                    loginBody(username, password),
                    null,
                    null,
                    properties.getLoginTimeout());
        } catch (NewApiRejectedException rejected) {
            throw new NewApiHttpException(401, rejected.getMessage());
        }
        JsonNode data = data(response, "New API login");
        String accessToken = text(data, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new LlmInvocationException(
                    "New API login response did not contain an access token");
        }
        JsonNode user = data.get("user");
        if (user == null || !user.isObject()) {
            throw new LlmInvocationException("New API login response did not contain a user");
        }
        String responseUsername = text(user, "username");
        if (username.equals(responseUsername)) {
            return new NewApiLoginSession(
                    requiredLong(user, "id", "New API login user"),
                    responseUsername,
                    accessToken,
                    expiresAtEpochSecond(data));
        }
        throw new LlmInvocationException("New API login returned a different user");
    }

    /** {@code access_expires_at}（秒）；站点没给就返回 0，调用方按「不知道」处理。 */
    private static long expiresAtEpochSecond(JsonNode data) {
        JsonNode expires = data.get("access_expires_at");
        if (expires == null || !expires.canConvertToLong()) {
            return 0L;
        }
        long value = expires.asLong();
        return value > 0 ? value : 0L;
    }

    /**
     * Creates a user through New API's public registration endpoint.
     *
     * <p>This is the one write that does not need an administrator: the same endpoint the New API
     * sign-up page posts to. It answers {@code {"success": true}} and no user id, so a caller that
     * needs the stable id has to sign in afterwards. A refusal arrives as HTTP 200 with
     * {@code success: false}, which is why {@link NewApiRegistrationException} exists.</p>
     */
    /**
     * Registers without an email address, for deployments that do not verify email.
     *
     * <p>Kept as a named overload rather than an optional argument so the call site states whether
     * it has an address to forward.</p>
     */
    public void registerUser(String username, String password) {
        registerUser(username, password, null, null);
    }

    public void registerUser(String username, String password, String email, String verificationCode) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        /*
         * New API writes users.email only when its own email verification is on, and refuses the
         * registration without an address and a code in that case. Both fields are therefore
         * forwarded verbatim: the code belongs to the upstream in-memory map, so it can neither be
         * minted nor checked here.
         */
        if (email != null && !email.isBlank()) {
            body.put("email", email);
        }
        if (verificationCode != null && !verificationCode.isBlank()) {
            body.put("verification_code", verificationCode);
        }
        try {
            send("POST", "/api/user/register", body, null, null, properties.getLoginTimeout());
        } catch (NewApiRejectedException | NewApiHttpException refused) {
            // Both carry the upstream sentence; whether it is a 200 body or a 4xx envelope, the
            // reason words ("already exists", "verification code") are what classify the refusal.
            throw NewApiRegistrationException.fromMessage(refused.getMessage());
        } catch (LlmInvocationException failure) {
            // An unparseable or unexpected body is a failure, never a taken name.
            throw new NewApiRegistrationException(
                    NewApiRegistrationException.Kind.FAILED, failure.getMessage());
        }
    }

    /**
     * Asks New API to mail a sign-up verification code to {@code email}.
     *
     * <p>This is the only mail this site can trigger, and it is anonymous by design: the endpoint
     * the New API sign-up page uses, so open registration, the per-address mail limit and the
     * Turnstile gate upstream all apply unchanged. It refuses addresses that already belong to an
     * account, which is why an existing user cannot be sent a code here.</p>
     *
     * @throws NewApiRejectedException when the address is taken or the request is refused
     */
    public void sendEmailVerification(String email) {
        String path = "/api/verification?email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8);
        send("GET", path, null, null, null, properties.getLoginTimeout());
    }

    /**
     * Whether the upstream deployment currently requires an email code to register.
     *
     * <p>Read from the anonymous {@code GET /api/status}, the same flag the New API sign-up page
     * reads, so this site does not need an operator to keep two switches in step. Cached for a
     * minute; an unreachable upstream keeps the previous answer.</p>
     */
    public boolean isEmailVerificationEnabled() {
        Instant now = Instant.now();
        if (Duration.between(emailVerificationCheckedAt, now).compareTo(EMAIL_VERIFICATION_CACHE_TTL) < 0) {
            return emailVerificationEnabled;
        }
        synchronized (this) {
            now = Instant.now();
            if (Duration.between(emailVerificationCheckedAt, now)
                    .compareTo(EMAIL_VERIFICATION_CACHE_TTL) < 0) {
                return emailVerificationEnabled;
            }
            try {
                JsonNode response = send(
                        "GET", "/api/status", null, null, null, properties.getLoginTimeout());
                JsonNode data = response.get("data");
                emailVerificationEnabled = data != null
                        && data.path("email_verification").asBoolean(false);
            } catch (RuntimeException failure) {
                log.warn("Unable to read New API email verification setting: {}",
                        failure.toString());
            } finally {
                emailVerificationCheckedAt = now;
            }
            return emailVerificationEnabled;
        }
    }

    public List<NewApiAccessToken> listAccessTokens(String loginAccessToken) {
        JsonNode response = send(
                "GET",
                "/api/user/access_tokens",
                null,
                loginAccessToken,
                null,
                properties.getUserAccessTokenTimeout());
        JsonNode data = data(response, "New API access token list");
        JsonNode rawItems = data.get("items");
        if (rawItems == null || !rawItems.isArray()) {
            throw new LlmInvocationException(
                    "New API access token list response did not contain items");
        }
        List<NewApiAccessToken> tokens = new ArrayList<>();
        for (JsonNode item : rawItems) {
            String name = text(item, "name");
            if (name != null) {
                tokens.add(new NewApiAccessToken(
                        requiredLong(item, "id", "New API access token"),
                        name));
            }
        }
        return List.copyOf(tokens);
    }

    public NewApiAccessTokenCredential createAccessToken(String loginAccessToken,
                                                         String password,
                                                         String name,
                                                         List<String> scopes) {
        ObjectNode context = objectMapper.createObjectNode();
        ArrayNode scopeArray = context.putArray("scopes");
        scopes.forEach(scopeArray::add);
        context.put("expires_at", 0);

        String proof = verify(
                loginAccessToken,
                password,
                "access_token.generate",
                context);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        ArrayNode bodyScopes = body.putArray("scopes");
        scopes.forEach(bodyScopes::add);
        body.put("expires_at", 0);
        JsonNode response = send(
                "POST",
                "/api/user/access_tokens",
                body,
                loginAccessToken,
                proof,
                properties.getUserAccessTokenTimeout());
        JsonNode data = data(response, "New API access token creation");
        String token = text(data, "token");
        JsonNode item = data.get("item");
        if (token == null || token.isBlank() || item == null || !item.isObject()) {
            throw new LlmInvocationException(
                    "New API access token creation response was incomplete");
        }
        return new NewApiAccessTokenCredential(
                requiredLong(item, "id", "New API access token"),
                token);
    }

    public void updateAccessToken(String loginAccessToken,
                                  String password,
                                  long accessTokenId,
                                  String name,
                                  List<String> scopes) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("token_id", accessTokenId);
        ArrayNode scopeArray = context.putArray("scopes");
        scopes.forEach(scopeArray::add);

        String proof = verify(
                loginAccessToken,
                password,
                "access_token.update",
                context);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        ArrayNode bodyScopes = body.putArray("scopes");
        scopes.forEach(bodyScopes::add);
        send(
                "PATCH",
                "/api/user/access_tokens/" + accessTokenId,
                body,
                loginAccessToken,
                proof,
                properties.getUserAccessTokenTimeout());
    }

    public void deleteAccessToken(String loginAccessToken, String password, long accessTokenId) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("token_id", accessTokenId);
        String proof = verify(
                loginAccessToken,
                password,
                "access_token.revoke",
                context);
        send(
                "DELETE",
                "/api/user/access_tokens/" + accessTokenId,
                null,
                loginAccessToken,
                proof,
                properties.getUserAccessTokenTimeout());
    }

    /**
     * Deletes the signed-in New API account itself, using only that user's own credentials.
     *
     * <p>New API guards account deletion with a step-up proof, so a password verification has to be
     * consumed first: {@code DELETE /api/user/self} answers {@code SECURITY_PROOF_REQUIRED} without
     * the {@code X-Security-Proof} header. The same password then opens the login session, which
     * means the caller needs the plaintext password, not a hash.</p>
     *
     * <p>New API marks the row deleted and revokes every session and access token. The row itself
     * stays, and with it the username: registering that name again is refused with "Username
     * already exists or has been deleted". {@link #hardDeleteUser(long)} is what actually frees it.
     * Two refusals are worth naming: the super administrator cannot delete itself, and an account
     * whose last credential is a third-party binding has no password method to verify with.</p>
     *
     * @param loginAccessToken dashboard access token from {@link #login(String, String)}
     * @param password          plaintext password of that same account, used for the proof
     * @throws NewApiHttpException when New API refuses (for example a root account, or no verifier)
     */
    public void deleteUser(String loginAccessToken, String password) {
        String proof = verify(loginAccessToken, password, "account.delete", null);
        send(
                "DELETE",
                "/api/user/self",
                null,
                loginAccessToken,
                proof,
                properties.getUserAccessTokenTimeout());
    }

    /**
     * Removes a New API user row permanently, releasing its username.
     *
     * <p>Only an administrator can do this, and only through {@code DELETE /api/user/{id}}, which
     * runs {@code HardDeleteUserById}: the row disappears, so the unique index no longer holds the
     * name and a later registration can take it. The endpoint also demands a step-up proof bound to
     * this exact target — the context is part of the signed proof, so a proof for one account
     * cannot be replayed against another.</p>
     *
     * <p>Configuration decides whether this runs at all: without
     * {@link NewApiProperties#hasAdminCredentials()} the caller keeps the soft-deleted row and the
     * username stays reserved. A 404 means the row is already gone, which is the outcome the caller
     * wants.</p>
     *
     * @param newapiUserId id of the New API user to remove
     * @throws NewApiHttpException when the administrator cannot sign in, or New API refuses
     */
    public void hardDeleteUser(long newapiUserId) {
        if (!properties.hasAdminCredentials()) {
            throw new IllegalStateException(
                    "No New API administrator is configured, so a username cannot be freed");
        }
        String adminPassword = properties.getAdminPassword();
        NewApiLoginSession admin = login(properties.getAdminUsername(), adminPassword);
        ObjectNode context = objectMapper.createObjectNode();
        context.put("user_id", newapiUserId);
        String proof = verify(admin.accessToken(), adminPassword, "admin.user.delete", context);
        try {
            send(
                    "DELETE",
                    "/api/user/" + newapiUserId,
                    null,
                    admin.accessToken(),
                    proof,
                    properties.getUserAccessTokenTimeout());
        } catch (NewApiHttpException missing) {
            if (missing.statusCode() != 404) {
                throw missing;
            }
        } catch (NewApiRejectedException tombstone) {
            /*
             * New API answers "no such row" with HTTP 200 and success=false, so the 404 branch
             * above never fires for it. What reaches here is a tombstone left by a previous
             * self-deletion: the account is already unusable, so there is nothing left to delete.
             * The row itself survives and keeps the username reserved — no endpoint here can
             * release it — so this is reported as done upstream rather than retried forever.
             */
            if (!isMissingRow(tombstone)) {
                throw tombstone;
            }
            log.info("New API user {} was already removed upstream; the deleted row may still "
                    + "hold its username", newapiUserId);
        }
    }

    /**
     * Whether the refusal means "that row is not there", as opposed to a permission or validation
     * error. Matched on GORM's own sentence because New API returns it inside a 200 body, where
     * there is no status code to read.
     */
    private static boolean isMissingRow(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null
                && message.toLowerCase(Locale.ROOT).contains("record not found");
    }

    public Optional<NewApiToken> findExactToken(String pat,
                                                String name,
                                                long expectedUserId) {
        JsonNode response = send(
                "GET",
                "/api/token/search?keyword=%s&p=1&page_size=100".formatted(queryValue(name)),
                null,
                pat,
                null,
                properties.getTokenManagementTimeout());
        List<NewApiToken> matches = new ArrayList<>();
        for (JsonNode item : items(response, "New API token search")) {
            String itemName = text(item, "name");
            long itemUserId = requiredLong(item, "user_id", "New API token");
            if (name.equals(itemName) && itemUserId == expectedUserId) {
                matches.add(new NewApiToken(
                        requiredLong(item, "id", "New API token"),
                        itemUserId,
                        itemName,
                        booleanValue(item, "allow_user_quota_query", false)));
            }
        }
        return exactlyOneToken(matches, "New API relay token '" + name + "'");
    }

    public void validateAccessToken(String pat) {
        JsonNode response = send(
                "GET",
                "/api/token/?p=1&page_size=1",
                null,
                pat,
                null,
                properties.getTokenManagementTimeout());
        items(response, "New API access token validation");
    }

    /**
     * 当前用户在 New API 上可选的分组名。
     *
     * <p>{@code GET /api/user/self/groups} 需要用户身份，所以要用 PAT 而不是 relay key。
     * 返回值是「分组名 → 描述/倍率」的对象，这里只取键。</p>
     *
     * <p>建分组令牌前问一次这个，是为了把「账号根本无权使用该分组」提前变成一句能看懂的
     * 503，而不是建出一把调用时才 403 的令牌 —— rc.47 的 {@code POST /api/token/}
     * 不校验非 auto 分组的可见性。</p>
     */
    public List<String> fetchUserGroupNames(String pat) {
        JsonNode response = send(
                "GET",
                "/api/user/self/groups",
                null,
                pat,
                null,
                properties.getTokenManagementTimeout());
        JsonNode data = data(response, "New API user groups");
        if (!data.isObject()) {
            throw new LlmInvocationException("New API user groups response was not an object");
        }
        List<String> groups = new ArrayList<>();
        data.fieldNames().forEachRemaining(name -> {
            if (name != null && !name.isBlank()) {
                groups.add(name.strip());
            }
        });
        return groups;
    }

    public void createRelayToken(String pat, String name) {
        createRelayToken(pat, name, resolvedGroup());
    }

    /**
     * Creates a relay token bound to one explicit New API group.
     *
     * <p>Group is a single-valued column on the token, so reaching a model that only exists in
     * another group means having a token for that group. {@code group} must be the real group name
     * from {@code enable_groups} — the reserved value {@code auto} is rejected at request time by
     * some deployments (see {@code docs/new-api-billing.md} 5.1.1).</p>
     */
    public void createRelayToken(String pat, String name, String group) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("unlimited_quota", true);
        body.put("allow_user_quota_query", true);
        body.put("expired_time", -1);
        body.put("group", group == null || group.isBlank() ? resolvedGroup() : group.strip());
        send(
                "POST",
                "/api/token/",
                body,
                pat,
                null,
                properties.getTokenManagementTimeout());
    }

    public void updateRelayToken(String pat, long tokenId, String name) {
        updateRelayToken(pat, tokenId, name, resolvedGroup());
    }

    public void updateRelayToken(String pat, long tokenId, String name, String group) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", tokenId);
        body.put("name", name);
        body.put("unlimited_quota", true);
        body.put("allow_user_quota_query", true);
        body.put("expired_time", -1);
        body.put("group", group == null || group.isBlank() ? resolvedGroup() : group.strip());
        send(
                "PUT",
                "/api/token/",
                body,
                pat,
                null,
                properties.getTokenManagementTimeout());
    }

    public void deleteRelayToken(String pat, long tokenId) {
        send(
                "DELETE",
                "/api/token/" + tokenId,
                null,
                pat,
                null,
                properties.getTokenManagementTimeout());
    }

    public String revealToken(String pat, long tokenId) {
        JsonNode response = send(
                "POST",
                "/api/token/%d/key".formatted(tokenId),
                null,
                pat,
                null,
                properties.getTokenManagementTimeout());
        String key = text(data(response, "New API token reveal"), "key");
        if (key == null || key.isBlank()) {
            throw new LlmInvocationException(
                    "New API token reveal response did not contain a key");
        }
        return key;
    }

    public NewApiTokenUsage fetchUsage(String relayKey) {
        try {
            JsonNode response = send(
                    "GET",
                    "/api/usage/token/",
                    null,
                    relayKey,
                    null,
                    properties.getUsageTimeout());
            JsonNode data = data(response, "New API token usage");

            JsonNode userQuota = data.get("user_quota");
            if (userQuota == null || !userQuota.isObject()) {
                throw new ServiceUnavailableException(
                        "New API usage response did not contain user_quota");
            }
            long userTotalAvailable = strictNonNegativeLong(
                    userQuota, "total_available", "New API user quota");
            long userTotalUsed = strictNonNegativeLong(
                    userQuota, "total_used", "New API user quota");
            requireSafeSum(userTotalUsed, userTotalAvailable);

            JsonNode modelLimits = data.get("model_limits");
            boolean unlimitedQuota = booleanValue(data, "unlimited_quota", false);
            return new NewApiTokenUsage(
                    text(data, "name"),
                    tokenUsageQuota(data, "total_granted", unlimitedQuota),
                    strictNonNegativeLong(data, "total_used", "New API token usage"),
                    tokenUsageQuota(data, "total_available", unlimitedQuota),
                    unlimitedQuota,
                    modelLimits == null || modelLimits.isNull()
                            ? objectMapper.createObjectNode()
                            : modelLimits,
                    booleanValue(data, "model_limits_enabled", false),
                    strictNonNegativeLong(data, "expires_at", "New API token usage"),
                    userTotalUsed,
                    userTotalAvailable);
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException("New API usage service is unavailable");
        }
    }

    /**
     * Reads the caller's own log page.
     *
     * <p>No {@code type} filter is sent on purpose. New API settles a failed async task (video,
     * task relay) by refunding the pre-charged quota and writing a <em>refund</em> row
     * ({@code type=6}); asking only for {@code type=2} silently hid those rows, so a user whose
     * generation failed saw the charge but never the refund. The page therefore carries every
     * kind, and the caller labels them (see {@code TokenConsumptionLogResponse.Item.type}).
     * Pagination stays exact because the filtering happens nowhere else.</p>
     */
    public NewApiConsumptionLogPage fetchConsumptionLogs(String pat, int page, int pageSize) {
        try {
            JsonNode response = send(
                    "GET",
                    "/api/log/self?p=%d&page_size=%d".formatted(page, pageSize),
                    null,
                    pat,
                    null,
                    properties.getUsageTimeout());
            JsonNode data = data(response, "New API consumption logs");
            int responsePage = positiveInt(data, "page", "New API consumption logs");
            int responsePageSize = positiveInt(
                    data, "page_size", "New API consumption logs");
            if (responsePage != page || responsePageSize != pageSize) {
                throw new LlmInvocationException(
                        "New API consumption logs returned unexpected pagination");
            }
            long total = nonNegativeLong(
                    data, "total", "New API consumption logs");
            JsonNode rawItems = data.get("items");
            if (rawItems == null || !rawItems.isArray()) {
                throw new LlmInvocationException(
                        "New API consumption logs response did not contain items");
            }

            List<NewApiConsumptionLog> result = new ArrayList<>();
            for (JsonNode item : rawItems) {
                result.add(consumptionLog(item));
            }
            return new NewApiConsumptionLogPage(
                    responsePage, responsePageSize, total, List.copyOf(result));
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException(
                    "New API consumption log service is unavailable");
        }
    }

    public long redeemRedemption(String loginAccessToken, String key) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("key", key);
        JsonNode response = send(
                "POST",
                "/api/user/topup",
                body,
                loginAccessToken,
                null,
                properties.getTokenManagementTimeout());
        JsonNode data = data(response, "New API redemption");
        if (!data.isIntegralNumber()) {
            throw new LlmInvocationException(
                    "New API redemption response did not contain a quota");
        }
        BigInteger quota = data.bigIntegerValue();
        if (quota.signum() <= 0 || quota.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new LlmInvocationException(
                    "New API redemption response contained an invalid quota");
        }
        return quota.longValueExact();
    }

    /**
     * Reads New API's pricing catalogue ({@code GET /api/pricing}).
     *
     * <p>Anonymous by default. When {@link NewApiProperties#hasCatalogCredentials()} is set the
     * request carries {@code Authorization: Bearer <session token>} of that account, which is what
     * a deployment needs once the {@code pricing} header-nav module is switched to
     * {@code requireAuth} — New API then answers 401 to callers without a session.</p>
     *
     * <p>New API filters the plaza by the caller's usable groups, so the response — and therefore
     * the synced local catalogue — covers the groups that account can use; it is not a global
     * model list.</p>
     *
     * <p>Newer New API builds publish {@code /api/v1/model-plaza} with a per-tier table, but the
     * gateway this deployment talks to (v1.0.0-rc.45) has no such route. {@code /api/models/public}
     * is not a public endpoint either: it matches {@code /api/models/:id} and needs admin auth.
     * {@code /api/pricing} is the only catalogue it exposes, so its flat model list is
     * folded into the same {@link NewApiModelPlaza} shape the rest of the module already consumes.
     * New API quota units are converted to USD here:</p>
     *
     * <ul>
     *   <li>{@code quota_type = 0}: USD/token = {@code model_ratio × group_ratio / quotaPerUnit};</li>
     *   <li>{@code quota_type = 1}: USD/request = {@code model_price × group_ratio};</li>
     *   <li>{@code billing_mode} present (tier expressions): the endpoint publishes the expression
     *       instead of a tier table, and its coefficients already are USD per million tokens, so the
     *       price is read from the expression itself ({@link BillingExprPrices}). Deriving it from
     *       {@code model_ratio} instead would print a confidently wrong number for image models.</li>
     * </ul>
     */
    public NewApiModelPlaza fetchModelPlaza() {
        JsonNode response = pricingResponse();
        if (response == null || !response.isObject()) {
            throw new LlmInvocationException(
                    "New API pricing response was not a JSON object");
        }
        JsonNode items = response.get("data");
        if (items == null || !items.isArray()) {
            throw new LlmInvocationException(
                    "New API pricing response did not contain a model list");
        }
        Map<String, BigDecimal> groupRatios = groupRatios(response.get("group_ratio"));

        Map<String, String> groupLabels = groupLabels(response.get("usable_group"));

        Map<String, List<NewApiModelPlaza.Model>> modelsByGroup = new LinkedHashMap<>();
        for (JsonNode item : items) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String name = text(item, "model_name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String platform = firstText(item.get("supported_endpoint_types"));
            // This array is the model's own enable_groups, not the caller's view of it: New API
            // drops whole rows the caller cannot reach, but leaves every group name in place.
            List<String> groups = textValues(item.get("enable_groups"));
            if (groups.isEmpty()) {
                groups = List.of("default");
            }
            for (String group : groups) {
                BigDecimal groupRatio = groupRatios.getOrDefault(group, BigDecimal.ONE);
                modelsByGroup
                        .computeIfAbsent(group, ignored -> new ArrayList<>())
                        .add(new NewApiModelPlaza.Model(
                                name.strip(),
                                platform,
                                pricingFor(item, groupRatio),
                                groups));
            }
        }

        if (modelsByGroup.isEmpty()) {
            throw new LlmInvocationException(
                    "New API pricing response published no models");
        }

        List<NewApiModelPlaza.Group> groups = new ArrayList<>();
        modelsByGroup.forEach((name, models) -> groups.add(
                new NewApiModelPlaza.Group(null, name, null, models)));
        return new NewApiModelPlaza(
                "New API public pricing catalogue",
                groups,
                availableGroups(groupLabels, groupRatios));
    }

    /**
     * Fetches {@code /api/pricing}, signing in when the plaza is not public.
     *
     * <p>The retry exists because a cached session token dies silently: New API revokes it when the
     * account's sessions are pruned, its password changes, or the site caps concurrent sessions.
     * One fresh sign-in is enough to tell "stale token" apart from "the account lost access", and
     * retrying more would only burn sign-in attempts against the upstream limiter.</p>
     */
    private JsonNode pricingResponse() {
        if (!properties.hasCatalogCredentials()) {
            return send("GET", PRICING_PATH, null, null, null, properties.getCatalogTimeout());
        }
        try {
            return send(
                    "GET",
                    PRICING_PATH,
                    null,
                    catalogAccessToken(),
                    null,
                    properties.getCatalogTimeout());
        } catch (NewApiHttpException rejected) {
            if (!rejected.isAuthenticationFailure()) {
                throw rejected;
            }
            log.info("New API rejected the catalogue session; signing in again");
            synchronized (this) {
                logoutQuietly(catalogSessionToken);
                catalogSessionToken = null;
                catalogSessionExpiresAt = 0L;
            }
            return send(
                    "GET",
                    PRICING_PATH,
                    null,
                    catalogAccessToken(),
                    null,
                    properties.getCatalogTimeout());
        }
    }

    /** Cached session token of the catalogue account, signing in on first use or after a rejection. */
    private synchronized String catalogAccessToken() {
        // 复用：只要没到过期时间（留 60 秒余量）就绝不重新登录。站点的登录会话有上限
        // （AUTH_SESSION_LIMIT），多登一次就多占一个槽位，攒满之后同步全线 409。
        if (catalogSessionToken != null && !catalogSessionToken.isBlank()
                && !catalogSessionExpired(nowSeconds())) {
            return catalogSessionToken;
        }
        long now = System.currentTimeMillis();
        if (now < catalogLoginBlockedUntil) {
            // 上游登录刚失败过（典型是 409 AUTH_SESSION_LIMIT）：退避窗口内直接报同样的原因，
            // 不要每 5 分钟的同步周期再撞一次——那只会把日志刷满，也不会自己好。
            throw new ServiceUnavailableException(
                    "New API catalogue sign-in is backing off after a failure: "
                            + catalogLoginLastError);
        }
        // 换新令牌前先把旧会话释放掉：登出是 GET /api/user/logout，站点一定提供；
        // 失败（令牌已过期/已失效）就忽略——那种情况下槽位本来也会自己回收。
        logoutQuietly(catalogSessionToken);
        catalogSessionToken = null;
        try {
            NewApiLoginSession session = login(
                    properties.getCatalogUsername(), properties.getCatalogPassword());
            catalogSessionToken = session.accessToken();
            catalogSessionExpiresAt = session.expiresAtEpochSecond();
            catalogLoginFailures = 0;
            catalogLoginBlockedUntil = 0L;
            catalogLoginLastError = null;
            return catalogSessionToken;
        } catch (RuntimeException failure) {
            catalogLoginFailures += 1;
            // 1、2、4、8、16、30、30… 分钟：站点上的会话要人工清，撞得再勤也进不去。
            long minutes = Math.min(30L, 1L << Math.min(catalogLoginFailures - 1, 5));
            catalogLoginBlockedUntil = System.currentTimeMillis() + minutes * 60_000L;
            catalogLoginLastError = describeLoginFailure(failure);
            log.warn("New API catalogue sign-in failed ({}); next attempt in {} min",
                    catalogLoginLastError, minutes);
            throw new ServiceUnavailableException(
                    "New API catalogue sign-in failed: " + catalogLoginLastError);
        }
    }

    private boolean catalogSessionExpired(long nowSeconds) {
        return catalogSessionExpiresAt > 0 && nowSeconds >= catalogSessionExpiresAt - 60L;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 释放一个不再使用的登录会话；站点不认这个令牌时静默跳过。 */
    private void logoutQuietly(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            send("GET", "/api/user/logout", null, accessToken, null,
                    properties.getCatalogTimeout());
        } catch (RuntimeException ignored) {
            // 会话已经过期或被踢掉，本来也占不到槽位了。
        }
    }

    /**
     * 把登录失败翻译成能照做的原因。
     *
     * <p>实测 api.demo-relay.online 达到会话上限时只回 {@code 409} + {@code "Conflict"}，
     * 光看日志完全不知道要清会话。{@code AUTH_SESSION_LIMIT} 是这个站点返回的业务码
     * （正文里带，状态行里不带）。</p>
     */
    private static String describeLoginFailure(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        boolean sessionLimit = message.contains("AUTH_SESSION_LIMIT")
                || (failure instanceof NewApiHttpException http && http.statusCode() == 409);
        if (sessionLimit) {
            return "上游账号的登录会话数已达上限（AUTH_SESSION_LIMIT）。"
                    + "请在 New API 后台清理该账号的旧会话（或调高会话上限）后再同步";
        }
        return message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * Reads {@code usable_group} ({@code name: description}) into the selectable-group catalogue.
     *
     * <p>Anonymous callers get the site-wide user-selectable groups, which is exactly the list a
     * relay may pick from. A group missing here is not offered to users even if some model is
     * served in it, so it must not become a routing candidate.</p>
     */
    private static Map<String, String> groupLabels(JsonNode node) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return labels;
        }
        node.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                return;
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                labels.put(name, null);
            } else if (value.isTextual()) {
                labels.put(name, value.textValue());
            } else {
                labels.put(name, text(value, "desc"));
            }
        });
        return labels;
    }

    private static List<NewApiModelPlaza.GroupInfo> availableGroups(Map<String, String> labels,
                                                                   Map<String, BigDecimal> ratios) {
        Set<String> names = new LinkedHashSet<>(labels.keySet());
        names.addAll(ratios.keySet());
        List<NewApiModelPlaza.GroupInfo> result = new ArrayList<>(names.size());
        for (String name : names) {
            result.add(new NewApiModelPlaza.GroupInfo(name, labels.get(name), ratios.get(name)));
        }
        return result;
    }

    private static Map<String, BigDecimal> groupRatios(JsonNode node) {
        Map<String, BigDecimal> ratios = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return ratios;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isNumber()) {
                ratios.put(entry.getKey(), value.decimalValue());
            }
        });
        return ratios;
    }

    private static List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode value : node) {
            if (value != null && value.isTextual() && !value.textValue().isBlank()) {
                values.add(value.textValue().strip());
            }
        }
        return values;
    }

    private static String firstText(JsonNode node) {
        List<String> values = textValues(node);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Converts one {@code /api/pricing} entry into the plaza pricing shape, expressed in USD.
     * Returns {@code null} when New API publishes no usable price for the model.
     */
    private NewApiModelPlaza.Pricing pricingFor(JsonNode item, BigDecimal groupRatio) {
        String billingMode = text(item, "billing_mode");
        if (billingMode != null && !billingMode.isBlank()) {
            return expressionPricing(
                    billingMode.strip(), text(item, "billing_expr"), groupRatio);
        }

        int quotaType = item.path("quota_type").asInt(0);
        if (quotaType == 1) {
            BigDecimal modelPrice = decimalOrNull(item, "model_price");
            if (modelPrice == null || modelPrice.signum() <= 0) {
                return null;
            }
            return new NewApiModelPlaza.Pricing(
                    null,
                    null, null, null, null, null, null, null,
                    modelPrice.multiply(groupRatio),
                    List.of());
        }

        BigDecimal ratio = decimalOrNull(item, "model_ratio");
        if (ratio == null || ratio.signum() <= 0) {
            return null;
        }
        BigDecimal quotaPerUnit = properties.getQuotaPerUnit();
        if (quotaPerUnit == null || quotaPerUnit.signum() <= 0) {
            quotaPerUnit = new BigDecimal("500000");
        }
        BigDecimal inputPrice = ratio
                .multiply(groupRatio)
                .divide(quotaPerUnit, 12, RoundingMode.HALF_UP);
        return new NewApiModelPlaza.Pricing(
                null,
                inputPrice,
                scaledBy(inputPrice, decimalOrNull(item, "completion_ratio")),
                scaledBy(inputPrice, decimalOrNull(item, "create_cache_ratio")),
                null,
                scaledBy(inputPrice, decimalOrNull(item, "cache_ratio")),
                null,
                null,
                null,
                List.of());
    }

    /**
     * Reads a tier expression into the price shape this plaza keeps.
     *
     * <p>New API publishes {@code billing_expr} for dynamically billed models but never publishes the
     * evaluated tier table. The expression is still readable: its coefficients are USD per million
     * tokens, so each one is divided back down to USD per token, which is what the rest of the module
     * expects. Context-length conditions become intervals, and the first (cheapest-context) tier is
     * also reported as the model's headline price. An expression that depends on wall-clock windows or
     * request rules cannot be answered from a catalogue, so it stays unpriced instead of guessed.</p>
     */
    private NewApiModelPlaza.Pricing expressionPricing(
            String billingMode, String expression, BigDecimal groupRatio) {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(expression);
        if (tiers.isEmpty()) {
            return new NewApiModelPlaza.Pricing(
                    billingMode, null, null, null, null, null, null, null, null, List.of());
        }
        BillingExprPrices.Coefficients leading = tiers.get(0).coefficients();
        List<NewApiModelPlaza.Interval> intervals = tiers.size() < 2
                ? List.of()
                : tiers.stream().map(tier -> intervalOf(tier, groupRatio)).toList();
        return new NewApiModelPlaza.Pricing(
                billingMode,
                perToken(leading.input(), groupRatio),
                perToken(leading.output(), groupRatio),
                perToken(leading.cacheWrite(), groupRatio),
                perToken(leading.cacheWrite1h(), groupRatio),
                perToken(leading.cacheRead(), groupRatio),
                perToken(leading.imageInput(), groupRatio),
                perToken(leading.imageOutput(), groupRatio),
                perRequest(leading.perRequest(), groupRatio),
                intervals);
    }

    private static NewApiModelPlaza.Interval intervalOf(
            BillingExprPrices.Tier tier, BigDecimal groupRatio) {
        BillingExprPrices.Coefficients coefficients = tier.coefficients();
        return new NewApiModelPlaza.Interval(
                tier.minTokens(),
                tier.maxTokens(),
                perToken(coefficients.input(), groupRatio),
                perToken(coefficients.output(), groupRatio),
                perToken(coefficients.cacheWrite(), groupRatio),
                perToken(coefficients.cacheWrite1h(), groupRatio),
                perToken(coefficients.cacheRead(), groupRatio),
                perRequest(coefficients.perRequest(), groupRatio));
    }

    /** USD per million tokens to USD per token, with the group ratio applied first. */
    private static BigDecimal perToken(BigDecimal usdPerMillion, BigDecimal groupRatio) {
        return usdPerMillion == null
                ? null
                : usdPerMillion.multiply(groupRatio).divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal perRequest(BigDecimal usd, BigDecimal groupRatio) {
        return usd == null ? null : usd.multiply(groupRatio);
    }

    private static BigDecimal scaledBy(BigDecimal price, BigDecimal factor) {
        return factor == null ? null : price.multiply(factor);
    }

    private NewApiConsumptionLog consumptionLog(JsonNode item) {
        // New API keeps the money direction in `type`, not in the sign of `quota`. Anything
        // outside the documented range would be a contract break, so it is rejected rather than
        // silently rendered as a consume row.
        long type = strictNonNegativeLong(item, "type", "New API consumption log");
        if (type < 1 || type > 7) {
            throw new LlmInvocationException(
                    "New API consumption log contained an unexpected type");
        }
        return new NewApiConsumptionLog(
                requiredLong(item, "id", "New API consumption log"),
                (int) type,
                requiredTimestamp(item, "created_at", "New API consumption log"),
                textOrDefault(item, "model_name", ""),
                textOrDefault(item, "token_name", ""),
                strictNonNegativeLong(item, "quota", "New API consumption log"),
                strictNonNegativeLong(
                        item, "prompt_tokens", "New API consumption log"),
                strictNonNegativeLong(
                        item, "completion_tokens", "New API consumption log"),
                strictNonNegativeLong(item, "use_time", "New API consumption log"),
                booleanValue(item, "is_stream", false));
    }

    private ObjectNode loginBody(String username, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        PasswordPayload payload = passwordPayload(password);
        if (payload.encrypted()) {
            body.put("password_encrypted", payload.value());
            body.put("encryption_key_id", payload.keyId());
        } else {
            body.put("password", password);
        }
        return body;
    }

    private String verify(String loginAccessToken,
                          String password,
                          String scope,
                          JsonNode context) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("scope", scope);
        body.put("method", "password");
        PasswordPayload payload = passwordPayload(password);
        if (payload.encrypted()) {
            body.put("password_encrypted", payload.value());
            body.put("encryption_key_id", payload.keyId());
        } else {
            body.put("password", password);
        }
        // Scopes that carry no parameters must arrive with the field absent, not null. New API
        // decodes the context into json.RawMessage: the literal null is a non-empty raw value, so
        // {"context":null} fails the "must be a JSON object" check and the whole call is refused
        // with SECURITY_CONTEXT_INVALID. Account deletion has no context, so this matters there.
        if (context != null && !context.isNull()) {
            body.set("context", context);
        }
        JsonNode response = send(
                "POST",
                "/api/verify",
                body,
                loginAccessToken,
                null,
                properties.getUserAccessTokenTimeout());
        String proof = text(data(response, "New API verification"), "proof_token");
        if (proof == null || proof.isBlank()) {
            throw new LlmInvocationException(
                    "New API verification response did not contain a proof token");
        }
        return proof;
    }

    private PasswordPayload passwordPayload(String password) {
        if (password == null || password.isBlank()) {
            throw new ServiceUnavailableException("New API password is empty");
        }
        JsonNode response = send(
                "GET",
                "/api/user/login/encryption-key",
                null,
                null,
                null,
                properties.getLoginTimeout());
        JsonNode data = data(response, "New API password encryption key");
        boolean enabled = booleanValue(data, "enabled", false);
        if (!enabled) {
            return new PasswordPayload(false, null, password);
        }
        String keyId = text(data, "kid");
        String publicKey = text(data, "public_key");
        if (keyId == null || keyId.isBlank() || publicKey == null || publicKey.isBlank()) {
            throw new LlmInvocationException(
                    "New API password encryption is enabled but its public key is missing");
        }
        return new PasswordPayload(true, keyId, encryptPasswordV2(password, keyId, publicKey));
    }

    private String encryptPasswordV2(String password, String keyId, String publicKeyPem) {
        try {
            PublicKey publicKey = parsePublicKey(publicKeyPem);
            byte[] aesKey = new byte[AES_KEY_BYTES];
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(aesKey);
            secureRandom.nextBytes(nonce);

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec oaep = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    new PSource.PSpecified(PASSWORD_V2_LABEL.getBytes(StandardCharsets.UTF_8)));
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, oaep);
            byte[] wrappedKey = rsa.doFinal(aesKey);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            aes.updateAAD((PASSWORD_V2_LABEL + ":" + keyId).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = aes.doFinal(password.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder base64 = Base64.getEncoder();
            return "v2.%s.%s.%s".formatted(
                    base64.encodeToString(wrappedKey),
                    base64.encodeToString(nonce),
                    base64.encodeToString(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new LlmInvocationException(
                    "Unable to encrypt the New API password", exception);
        }
    }

    private static PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        String normalized = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private JsonNode send(String method,
                          String path,
                          JsonNode body,
                          String bearerToken,
                          String securityProof,
                          Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Accept", CONTENT_TYPE);
        if (bearerToken != null && !bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (securityProof != null && !securityProof.isBlank()) {
            request.header("X-Security-Proof", securityProof);
        }
        if (body != null) {
            request.header("Content-Type", CONTENT_TYPE);
            request.method(method, HttpRequest.BodyPublishers.ofString(
                    serialize(body), StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ServiceUnavailableException("New API request timed out");
        } catch (IOException exception) {
            throw new ServiceUnavailableException("New API is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("New API request was interrupted");
        }

        JsonNode node = parse(response.body(), path);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new NewApiHttpException(
                    status,
                    "New API request failed at %s (HTTP %d)%s"
                            .formatted(pathOnly(path), status, responseMessage(node)));
        }
        requireBusinessSuccess(node, path);
        return node;
    }

    private JsonNode parse(String body, String path) {
        if (body == null || body.isBlank()) {
            return objectMapper.getNodeFactory().nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException(
                    "New API returned an invalid JSON response at " + pathOnly(path), exception);
        }
    }

    private static void requireBusinessSuccess(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode success = node.get("success");
        if (success != null && success.isBoolean() && !success.booleanValue()) {
            throw new NewApiRejectedException(
                    "New API rejected the request at " + pathOnly(path)
                            + responseMessage(node));
        }
        JsonNode code = node.get("code");
        if (code != null && code.isBoolean() && !code.booleanValue()) {
            throw new NewApiRejectedException(
                    "New API rejected the request at " + pathOnly(path)
                            + responseMessage(node));
        }
    }

    private static String responseMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode message = node.get("message");
        if (message == null || !message.isTextual() || message.textValue().isBlank()) {
            return "";
        }
        String normalized = message.textValue().replaceAll("\\s+", " ").strip();
        if (normalized.length() > MAX_ERROR_LENGTH) {
            normalized = normalized.substring(0, MAX_ERROR_LENGTH);
        }
        return ": " + normalized;
    }

    private JsonNode data(JsonNode response, String operation) {
        if (response == null || !response.isObject()) {
            throw new LlmInvocationException(operation + " response was not a JSON object");
        }
        JsonNode data = response.get("data");
        if (data == null || data.isNull()) {
            throw new LlmInvocationException(operation + " response did not contain data");
        }
        return data;
    }

    private IteratorLike items(JsonNode response, String operation) {
        JsonNode data = data(response, operation);
        JsonNode items = data.get("items");
        if (items == null || !items.isArray()) {
            throw new LlmInvocationException(operation + " response did not contain items");
        }
        return new IteratorLike(items);
    }

    private static Optional<NewApiToken> exactlyOneToken(
            List<NewApiToken> matches, String description) {
        if (matches.size() > 1) {
            throw new LlmInvocationException(
                    "New API returned more than one exact match for " + description);
        }
        return matches.stream().findFirst();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static boolean booleanValue(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new LlmInvocationException(
                    "New API response field '" + field + "' was not a boolean");
        }
        return value.booleanValue();
    }

    private static long requiredLong(JsonNode node, String field, String description) {
        long value = strictNonNegativeLong(node, field, description);
        if (value <= 0) {
            throw new LlmInvocationException(
                    description + " response field '" + field + "' was not positive");
        }
        return value;
    }

    private static long strictNonNegativeLong(JsonNode node,
                                              String field,
                                              String description) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new ServiceUnavailableException(
                    description + " response field '" + field + "' was missing or invalid");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new ServiceUnavailableException(
                    description + " response field '" + field + "' was outside the valid range");
        }
        return integer.longValueExact();
    }

    private static long requiredTimestamp(JsonNode node,
                                          String field,
                                          String description) {
        long value = requiredLong(node, field, description);
        try {
            Instant.ofEpochSecond(value);
        } catch (DateTimeException exception) {
            throw new LlmInvocationException(
                    description + " response field '" + field
                            + "' was outside the timestamp range");
        }
        return value;
    }

    private static long tokenUsageQuota(JsonNode node,
                                        String field,
                                        boolean unlimitedQuota) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new ServiceUnavailableException(
                    "New API token usage response field '" + field
                            + "' was missing or invalid");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            if (unlimitedQuota) {
                return 0L;
            }
            throw new ServiceUnavailableException(
                    "New API token usage response field '" + field
                            + "' was outside the valid range");
        }
        return integer.longValueExact();
    }

    private static long nonNegativeLong(JsonNode node,
                                        String field,
                                        String description) {
        return strictNonNegativeLong(node, field, description);
    }

    private static int positiveInt(JsonNode node,
                                   String field,
                                   String description) {
        long value = requiredLong(node, field, description);
        if (value > Integer.MAX_VALUE) {
            throw new ServiceUnavailableException(
                    description + " response field '" + field
                            + "' was outside the valid range");
        }
        return (int) value;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new LlmInvocationException(
                    "New API response field '" + field + "' was not an integer");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new LlmInvocationException(
                    "New API response field '" + field + "' was outside the valid range");
        }
        return integer.longValueExact();
    }

    private static void requireSafeSum(long used, long available) {
        try {
            Math.addExact(used, available);
        } catch (ArithmeticException exception) {
            throw new ServiceUnavailableException(
                    "New API user quota total is outside the valid range");
        }
    }

    private String resolvedGroup() {
        String group = properties.getGroup();
        return group == null || group.isBlank() ? "default" : group.strip();
    }

    private URI uri(String path) {
        String baseUrl = properties.resolvedBaseUrl();
        if (baseUrl.isBlank()) {
            throw new ServiceUnavailableException("New API base URL is not configured");
        }
        return URI.create(baseUrl + path);
    }

    private String serialize(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to serialize a New API request", exception);
        }
    }

    private static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String pathOnly(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private record PasswordPayload(boolean encrypted, String keyId, String value) {
    }

    private record IteratorLike(JsonNode array) implements Iterable<JsonNode> {

        @Override
        public java.util.Iterator<JsonNode> iterator() {
            return array.elements();
        }
    }
}
