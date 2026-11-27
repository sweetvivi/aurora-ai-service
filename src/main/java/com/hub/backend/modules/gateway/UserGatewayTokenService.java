package com.hub.backend.modules.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiAccessToken;
import com.hub.backend.infra.newapi.NewApiAccessTokenCredential;
import com.hub.backend.infra.newapi.NewApiBillingConfig;
import com.hub.backend.infra.newapi.NewApiConsumptionLog;
import com.hub.backend.infra.newapi.NewApiConsumptionLogPage;
import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiRetryPolicy;
import com.hub.backend.infra.newapi.NewApiToken;
import com.hub.backend.infra.newapi.NewApiTokenUsage;
import com.hub.backend.infra.newapi.NewApiUser;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.crypto.AesGcmTokenCipher;
import com.hub.backend.modules.gateway.deletion.NewApiAccountDeletionQueue;
import com.hub.backend.modules.gateway.dto.TokenConsumptionLogResponse;
import com.hub.backend.modules.gateway.dto.TokenUsageResponse;
import com.hub.backend.modules.gateway.entity.NewApiDeletionTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UserGatewayTokenService {

    private static final Logger log = LoggerFactory.getLogger(UserGatewayTokenService.class);

    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{6,})");
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)(Bearer\\s+)\\S+");
    private static final int MAX_ERROR_LENGTH = 512;
    private static final int MAX_NEW_API_NAME_LENGTH = 50;
    /**
     * 版本 2 起加了 {@code profile:read}：新版 New API 的 {@code /api/user/self/groups} 要求它
     * （权限清单里写的就是「View your profile, groups, and available models」）。少了它，
     * 分组懒建在读可用分组那一步被 403 挡住，令牌永远建不出来。改这个常量必须同时升版本号，
     * 否则已存在的 PAT 不会被补权限。
     */
    static final int CURRENT_PAT_SCOPE_VERSION = 2;
    static final List<String> PAT_SCOPES = List.of(
            "profile:read",
            "api_key:read",
            "api_key:write",
            "api_key:reveal",
            "usage:read");

    private final UserGatewayTokenStateStore stateStore;
    private final NewApiGroupTokenStateStore groupTokenStateStore;
    private final NewApiManagementClient newApi;
    private final NewApiAccountIdentityService accountIdentity;
    private final NewApiProperties properties;
    private final AesGcmTokenCipher cipher;
    private final SessionPasswordVault passwords;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final NewApiAccountDeletionQueue deletionQueue;

    public UserGatewayTokenService(UserGatewayTokenStateStore stateStore,
                                   NewApiGroupTokenStateStore groupTokenStateStore,
                                   NewApiManagementClient newApi,
                                   NewApiAccountIdentityService accountIdentity,
                                   NewApiProperties properties,
                                   AesGcmTokenCipher cipher,
                                   SessionPasswordVault passwords,
                                   ObjectMapper objectMapper,
                                   Clock clock,
                                   NewApiAccountDeletionQueue deletionQueue) {
        this.stateStore = stateStore;
        this.groupTokenStateStore = groupTokenStateStore;
        this.newApi = newApi;
        this.accountIdentity = accountIdentity;
        this.properties = properties;
        this.cipher = cipher;
        this.passwords = passwords;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.deletionQueue = deletionQueue;
    }

    /**
     * Returns the user's decrypted relay key or throws. There is deliberately no shared-key fallback.
     */
    public String requireRelayKey(CurrentUser user) {
        String tokenName = tokenName(user);
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = now();
        LocalDateTime leaseUntil = now.plus(properties.getProvisioningLease());

        for (int tries = 0; tries < 3; tries++) {
            UserGatewayTokenStateStore.Claim claim;
            try {
                claim = stateStore.claim(
                        user.userId(), user.siteId(), tokenName, owner, now, leaseUntil);
            } catch (DataIntegrityViolationException | CannotAcquireLockException conflict) {
                pauseForConcurrentProvisioning();
                continue;
            }

            if (claim.status() == UserGatewayTokenStateStore.ClaimStatus.READY) {
                return decrypt(claim.token());
            }
            if (claim.status() == UserGatewayTokenStateStore.ClaimStatus.BUSY) {
                throw new ServiceUnavailableException(
                        "The account relay token is being provisioned; retry shortly");
            }
            return provision(user, tokenName, owner, claim.token());
        }
        throw new ServiceUnavailableException(
                "The account relay token could not be claimed; retry shortly");
    }

    /**
     * Returns a relay key only when New API reports a positive user quota. The balance check is
     * deliberately performed before any account-specific AI work starts.
     */
    public String requireRelayCredential(CurrentUser user) {
        String relayKey = requireRelayKey(user);
        // Reuse the persisted snapshot when it is fresh: this runs before *every* AI call, and
        // /api/usage/token/ shares New API's 20-requests-per-20-minutes critical limiter.
        UserGatewayTokenStateStore.Snapshot cached = stateStore.find(user.userId()).orElse(null);
        long available;
        boolean unlimited;
        if (isUsageSnapshotUsable(cached)) {
            available = cached.totalAvailable();
            unlimited = cached.unlimitedQuota();
        } else {
            NewApiTokenUsage usage = fetchAndSyncUsage(user.userId(), relayKey);
            available = usage.userTotalAvailable();
            unlimited = usage.unlimitedQuota();
        }
        // An unlimited relay token legitimately reports total_available = 0, so it must not be
        // read as "out of money" — that is exactly what provisioning creates.
        if (!unlimited && available <= 0) {
            throw new PaymentRequiredException(UpstreamFailureText.INSUFFICIENT_BALANCE_TEXT);
        }
        return relayKey;
    }

    /**
     * True when a persisted usage snapshot is recent enough to stand in for a live New API call.
     *
     * <p>{@code lastSyncAt} records the last <em>successful</em> sync, so a later
     * {@code lastUsageError} does not invalidate it: the numbers in the row are still the ones New
     * API reported at that moment. Treating an error as "cache unusable" would make one hiccup —
     * including the very 429 this throttle exists to prevent — permanently disable the cache and
     * put us straight back on the unthrottled path.</p>
     */
    private boolean isUsageSnapshotUsable(UserGatewayTokenStateStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.lastSyncAt() == null) {
            return false;
        }
        Duration ttl = properties.getUsageCacheTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return !now().isAfter(snapshot.lastSyncAt().plus(ttl));
    }

    /**
     * 快照是否已经「旧到值得强制再读一次」。
     *
     * <p>没有同步时间视为足够旧：这类行本来就要读一次上游才能用。间隔为 0 或负数时
     * 等于关闭这道下限（与 {@code usage-cache-ttl} 的 0 语义保持一致）。</p>
     */
    private boolean isSnapshotOlderThan(
            UserGatewayTokenStateStore.Snapshot snapshot, Duration interval) {
        if (snapshot == null || snapshot.lastSyncAt() == null) {
            return true;
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return true;
        }
        return !now().isBefore(snapshot.lastSyncAt().plus(interval));
    }

    /**
     * 用户账号的 PAT 与远端 user id，供「按分组建令牌」复用。
     *
     * <p>分组令牌不是另一套账号：它必须是同一个 New API 用户自己名下的 Token。所以先在
     * {@link #requireRelayKey} 上把账号令牌开出来（顺带把 PAT 建好并加密落库），再从这里取 PAT。
     * 调用方拿到的是仍有效的 PAT，可以直接调 {@code /api/token/}。</p>
     *
     * <p>这一步可能抛出 {@code PasswordConfirmationRequiredException}：会话里没有明文密码
     * 且历史 PAT 已失效时，用户需要重新输入一次密码。分组令牌沿用了同一个账号，所以要的是
     * 同一份确认，而不是第二套凭据。</p>
     */
    public RelayBase requireRelayBase(CurrentUser user) {
        requireRelayKey(user);
        UserGatewayTokenStateStore.Snapshot state = stateStore.find(user.userId())
                .orElseThrow(() -> new ServiceUnavailableException(
                        "The account relay token is not available"));
        if (state.newapiUserId() == null) {
            throw new ServiceUnavailableException(
                    "The account relay token is not bound to a New API user yet");
        }
        if (state.patScopeVersion() < CURRENT_PAT_SCOPE_VERSION && hasEncryptedPat(state)) {
            // 旧 PAT 缺 profile:read，而 /api/user/self/groups 要它：分组懒建的可见性预检
            // 会一直 403。这里顺手补一次权限；补不上也不影响主流程（调用方把 403 当
            // 「查不到」放行），所以吞掉异常而不是让整次请求失败。
            try {
                return new RelayBase(requireDetailedUsagePat(user), state.newapiUserId());
            } catch (RuntimeException downgrade) {
                log.debug("Unable to upgrade the PAT scopes for user {}: {}",
                        user.userId(), safeError(downgrade));
            }
        }
        return new RelayBase(decryptPat(state), state.newapiUserId());
    }

    /** New API 令牌名前缀，分组令牌要与之保持一致的前缀约定。 */
    String relayTokenPrefix() {
        return tokenPrefix();
    }

    /** 账号级凭据：PAT 与它所属的 New API 用户。 */
    public record RelayBase(String pat, long newapiUserId) {
    }

    public TokenUsageResponse usage(CurrentUser user) {
        return usage(user, false);
    }

    /**
     * @param forceRefresh 调用方明确知道「刚刚扣过钱 / 退过款」，要求绕过 {@code usage-cache-ttl}
     *                     去上游对一次账。仍然受 {@code usage-force-refresh-interval} 约束：
     *                     没有这个下限，一个会自我刷新的页面就能把 New API 的关键限额
     *                     （20 次 / 20 分钟）吃干净，反而让真正的 AI 调用借不到额度。
     */
    public TokenUsageResponse usage(CurrentUser user, boolean forceRefresh) {
        String relayKey = requireRelayKey(user);
        UserGatewayTokenStateStore.Snapshot token = stateStore.find(user.userId())
                .orElseThrow(() -> new ServiceUnavailableException(
                        "The account relay token is not available"));
        // Same throttle as `requireRelayCredential`: a balance panel that polls is otherwise enough
        // on its own to exhaust New API's 20-requests-per-20-minutes budget.
        UserGatewayTokenStateStore.Snapshot updated = token;
        boolean staleEnoughForForce =
                forceRefresh && isSnapshotOlderThan(token, properties.getUsageForceRefreshInterval());
        if (staleEnoughForForce || !isUsageSnapshotUsable(token)) {
            fetchAndSyncUsage(user.userId(), relayKey);
            updated = stateStore.find(user.userId()).orElse(token);
        }
        NewApiBillingConfig billing = billingConfig();
        JsonNode limits = readModelLimits(updated.modelLimits());
        return new TokenUsageResponse(
                mask(relayKey),
                updated.tokenName(),
                updated.totalGranted(),
                updated.totalUsed(),
                updated.totalAvailable(),
                "CNY",
                cnyAmount(updated.totalGranted(), billing),
                cnyAmount(updated.totalUsed(), billing),
                cnyAmount(updated.totalAvailable(), billing),
                billing.quotaPerUnit(),
                billing.usdExchangeRate(),
                updated.unlimitedQuota(),
                limits,
                updated.modelLimitsEnabled(),
                toInstant(updated.expiresAt()),
                toInstant(updated.lastSyncAt()));
    }

    /**
     * Re-reads the New API quota snapshot after the balance changed outside the relay path.
     *
     * <p>A redemption credits the New API <em>user</em>, which the cached snapshot does not see
     * until {@code usageCacheTtl} expires; without this the wallet keeps showing the pre-redemption
     * balance for up to that TTL. Best effort on purpose: the credit is already committed, so a
     * failed refresh must not turn a successful redemption into an error — the TTL picks the new
     * quota up on the next read anyway.
     */
    public void refreshUsageSnapshot(CurrentUser user) {
        if (user == null || user.userId() == null) {
            return;
        }
        try {
            String relayKey = requireRelayKey(user);
            fetchAndSyncUsage(user.userId(), relayKey);
        } catch (RuntimeException exception) {
            log.warn("Failed to refresh the usage snapshot for user {}: {}",
                    user.userId(), safeError(exception));
        }
    }

    public TokenConsumptionLogResponse consumptionLogs(CurrentUser user,
                                                        int page,
                                                        int pageSize) {
        String pat = requireDetailedUsagePat(user);
        NewApiConsumptionLogPage logs = newApi.fetchConsumptionLogs(pat, page, pageSize);
        NewApiBillingConfig billing = billingConfig();
        List<TokenConsumptionLogResponse.Item> items = logs.items().stream()
                .map(item -> consumptionLogItem(item, billing))
                .toList();
        return new TokenConsumptionLogResponse(
                logs.page(), logs.pageSize(), logs.total(), items);
    }

    /**
     * Removes what a user owns on New API when they delete their local account.
     *
     * <p>This deletes the New API account itself, not just its keys: leaving the upstream account
     * behind would keep a billable identity — and its balance — reachable with credentials this
     * site no longer tracks. Keys are therefore removed first only as a fallback for when the
     * account deletion is refused (a super administrator, or an account whose only verifier is a
     * third-party binding).</p>
     *
     * <p>New API availability must not decide whether the local deletion can complete. A refusal is
     * logged and local ciphertext is cleared afterwards; a <em>temporary</em> failure (limit 429,
     * 5xx, timeout) is queued instead of dropped, so the remote row still gets removed once the
     * rate-limit window has passed — see {@link NewApiAccountDeletionQueue}.</p>
     */
    @AuditLog(action = "REVOKE_GATEWAY_TOKEN", target = "GATEWAY_TOKEN", id = "#user.userId()",
            before = "@auditProbes.token(#user.userId())",
            after = "@auditProbes.token(#user.userId())")
    public void cleanupAccountAccess(CurrentUser user) {
        if (user == null || user.userId() == null) {
            return;
        }

        UserGatewayTokenStateStore.Snapshot state =
                stateStore.find(user.userId()).orElse(null);

        // The account deletion needs the plaintext password. It is still in the vault because
        // deleteAccount() writes it there right before calling this, even when the session that
        // opened it has already expired.
        RemoteAccountCleanup cleanup =
                deleteRemoteAccount(user, accountIdentity.boundNewApiUserId(user.userId()).orElse(null));
        boolean remoteAccountHandled = cleanup.isResolved();

        if (!remoteAccountHandled && state != null) {
            // Fallback: the account survives, so at least strip the credentials that could be
            // used to spend on it from here. Best effort — a second failure is only logged.
            deleteRelayToken(user, state);
            deleteAccessToken(user, state);
        }

        if (state != null) {
            try {
                stateStore.disableAndClearCredentials(user.userId());
            } catch (RuntimeException exception) {
                log.warn("Unable to clear stored New API credentials for user {}: {}",
                        user.userId(), safeError(exception));
            }
        }

        // 分组令牌也是这个账号的凭据：账号被删/被清理后，本地密文不应再留着。
        try {
            for (NewApiGroupTokenStateStore.Snapshot snapshot
                    : groupTokenStateStore.findAll(user.userId())) {
                groupTokenStateStore.invalidate(user.userId(), snapshot.groupKey());
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to clear group relay tokens for user {}: {}",
                    user.userId(), safeError(exception));
        }

        if (remoteAccountHandled) {
            try {
                accountIdentity.forgetRemoteIdentity(user.userId());
            } catch (RuntimeException exception) {
                log.warn("Unable to clear the New API binding for user {}: {}",
                        user.userId(), safeError(exception));
            }
        }

        // The in-memory password must not outlive the account it belongs to.
        passwords.forget(user.userId());
    }

    /**
     * Deletes the New API account behind a local account that is being removed.
     *
     * @param remoteId the New API user id bound to this account, resolved by the caller because the
     *                 admin path deletes the local row (and its binding) before getting here
     * @return what happened to the upstream row; never a failure the caller has to handle
     */
    private RemoteAccountCleanup deleteRemoteAccount(CurrentUser user, Long remoteId) {
        if (!properties.isEnabled()) {
            return RemoteAccountCleanup.NOT_APPLICABLE;
        }
        if (user.username() == null || user.username().isBlank()) {
            return RemoteAccountCleanup.NOT_APPLICABLE;
        }
        if (remoteId == null) {
            // No binding means this account never reached New API, or the binding was already
            // cleared by an earlier deletion. Signing in by name to guess would risk deleting
            // somebody else's account.
            return RemoteAccountCleanup.NOT_APPLICABLE;
        }
        /*
         * With an administrator configured the row is removed outright, and the user's own
         * self-deletion is skipped entirely. Two reasons: the self-delete only marks the row
         * deleted, which keeps the username reserved forever; and both calls need a step-up
         * verification, so doing them back to back trips New API's rate limit on /api/verify and
         * the second one fails with 429.
         */
        if (properties.hasAdminCredentials()) {
            try {
                newApi.hardDeleteUser(remoteId);
                return RemoteAccountCleanup.DELETED;
            } catch (RuntimeException exception) {
                /*
                 * 429 / 5xx / 连不上：上游只是此刻忙。降到自助删除也是同样的限流，多打一次
                 * /api/verify 只会再吃一个 429 —— 所以这里不换招，直接把这次物理删除排队。
                 */
                if (deferDeletion(user, remoteId, user.username().strip(), null,
                        NewApiDeletionTaskEntity.MODE_ADMIN, exception)) {
                    return RemoteAccountCleanup.QUEUED;
                }
                log.warn("New API user {} was not removed permanently, falling back to "
                        + "self-deletion: {}", remoteId, safeError(exception));
            }
        }

        // Without a held password the remote account cannot be signed in to, let alone deleted.
        // Signing in again is the only way to produce one, and this flow is deleting the account.
        Optional<String> password = passwords.password(user.userId());
        if (password.isEmpty()) {
            log.warn("New API account {} for user {} was left in place: the plaintext password is "
                    + "not held by this server and no administrator is configured",
                    remoteId, user.userId());
            return RemoteAccountCleanup.LEFT_IN_PLACE;
        }

        try {
            NewApiLoginSession login = newApi.login(user.username().strip(), password.get());
            newApi.deleteUser(login.accessToken(), password.get());
            return RemoteAccountCleanup.DELETED;
        } catch (RuntimeException exception) {
            if (deferDeletion(user, remoteId, user.username().strip(), password.get(),
                    NewApiDeletionTaskEntity.MODE_SELF, exception)) {
                return RemoteAccountCleanup.QUEUED;
            }
            log.warn("Unable to delete the New API account for user {}: {}",
                    user.userId(), safeError(exception));
            return RemoteAccountCleanup.LEFT_IN_PLACE;
        }
    }

    /**
     * Finishes the remote side after an administrator deleted a local account.
     *
     * <p>Called after the local row is gone, so the caller has to hand in what used to describe it:
     * the binding row and the gateway token row are both {@code ON DELETE CASCADE}, and reading
     * them here would already be too late. The only credential an administrator's session has is
     * the shared management account — the user's own password is not available, and asking for it
     * would mean an operator knowing every user's password.</p>
     *
     * @param remoteId the New API user id the caller read before deleting the local row, or
     *                 {@code null} when this account never had one
     */
    public RemoteAccountCleanup deleteRemoteAccountOfDeletedUser(Long userId,
                                                                 String username,
                                                                 Long remoteId) {
        RemoteAccountCleanup cleanup =
                deleteRemoteAccount(new CurrentUser(userId, null, username), remoteId);
        // The in-memory password must not outlive the account it belongs to.
        passwords.forget(userId);
        return cleanup;
    }

    /**
     * 上游暂时不可用时，把「删这个远端账号」记成后台待办。
     *
     * <p>只有可重试的失败才排队：一次明确的拒绝（密码不对、root 不能删自己）重放一万次也是
     * 同一个结果，排进去只会在队列里空转到上限。排队成功即返回 true —— 对这个请求来说远端
     * 那一步已经交代出去了，不该再往下走备份路径（那些调用同样会撞上限流）。</p>
     *
     * <p>入队跟随调用方的事务：注销回滚时这条待办一起回滚，不会出现「本地还在、远端却已删」。</p>
     */
    private boolean deferDeletion(CurrentUser user,
                                  Long newapiUserId,
                                  String username,
                                  String password,
                                  String mode,
                                  RuntimeException failure) {
        if (!NewApiRetryPolicy.isRetryable(failure)) {
            return false;
        }
        boolean queued = deletionQueue.enqueue(
                user.userId(), newapiUserId, username, password, mode, safeError(failure));
        if (queued) {
            log.warn("New API account deletion for user {} hit a temporary upstream failure; "
                            + "queued for retry (mode={}): {}",
                    user.userId(), mode, safeError(failure));
        }
        return queued;
    }

    private void deleteRelayToken(CurrentUser user, UserGatewayTokenStateStore.Snapshot state) {
        if (state.newapiTokenId() == null || !hasEncryptedPat(state)) {
            return;
        }
        try {
            String pat = cipher.decrypt(
                    state.patCiphertext(), state.patNonce(), state.patTag());
            newApi.deleteRelayToken(pat, state.newapiTokenId());
        } catch (RuntimeException exception) {
            log.warn("Unable to remove New API relay token for user {}: {}",
                    user.userId(), safeError(exception));
        }
    }

    private void deleteAccessToken(CurrentUser user, UserGatewayTokenStateStore.Snapshot state) {
        if (state.newapiPatId() == null) {
            return;
        }
        try {
            NewApiLoginSession login = accountIdentity.login(user);
            newApi.deleteAccessToken(
                    login.accessToken(),
                    accountIdentity.loginPassword(user),
                    state.newapiPatId());
        } catch (RuntimeException exception) {
            log.warn("Unable to remove New API PAT for user {}: {}",
                    user.userId(), safeError(exception));
        }
    }

    private static BigDecimal cnyAmount(long quota, NewApiBillingConfig billing) {
        return BigDecimal.valueOf(quota)
                .multiply(billing.usdExchangeRate())
                .divide(billing.quotaPerUnit(), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal cnyConsumptionAmount(
            long quota,
            NewApiBillingConfig billing) {
        return BigDecimal.valueOf(quota)
                .multiply(billing.usdExchangeRate())
                .divide(billing.quotaPerUnit(), 4, RoundingMode.HALF_UP);
    }

    private static TokenConsumptionLogResponse.Item consumptionLogItem(
            NewApiConsumptionLog item,
            NewApiBillingConfig billing) {
        long totalTokens;
        try {
            totalTokens = Math.addExact(item.promptTokens(), item.completionTokens());
        } catch (ArithmeticException exception) {
            throw new ServiceUnavailableException(
                    "New API consumption token count is invalid");
        }
        return new TokenConsumptionLogResponse.Item(
                item.id(),
                Instant.ofEpochSecond(item.createdAt()),
                item.modelName(),
                item.tokenName(),
                item.promptTokens(),
                item.completionTokens(),
                totalTokens,
                item.quota(),
                cnyConsumptionAmount(item.quota(), billing),
                item.useTime(),
                item.stream(),
                logTypeName(item.type()));
    }

    /**
     * New API {@code LogType} → 前端使用的种类名。
     *
     * <p>失败任务的退款是 {@code 6}：金额是正的，方向由图例表达。未识别的类型归到
     * {@code system}，而不是让整页失败 —— 运维行不影响对账，但一条新类型不该让用户
     * 连自己的扣费记录都看不到。</p>
     */
    private static String logTypeName(int newApiType) {
        return switch (newApiType) {
            case 1 -> "topup";
            case 2 -> "consume";
            case 6 -> "refund";
            default -> "system";
        };
    }

    private String provision(CurrentUser user,
                             String tokenName,
                             String owner,
                             UserGatewayTokenStateStore.Snapshot state) {
        try {
            /*
             * Try the stored PAT first. Every call below (`/api/token/search`, `/api/token/`,
             * `/api/token/{id}/key`) accepts the PAT itself, and PAT_SCOPES already grants read,
             * write and reveal, so a still-valid PAT is enough to rebuild the relay key.
             *
             * This is what makes the first AI call after a restart work without asking for the
             * password: the vault is empty, but the PAT is encrypted in the row and almost always
             * still valid. Only an explicit 401/403 from New API — the PAT really was revoked —
             * falls through to the password path below.
             */
            if (state.newapiUserId() != null && hasEncryptedPat(state)) {
                String storedPat = decryptPat(state);
                try {
                    newApi.validateAccessToken(storedPat);
                    return completeProvision(
                            user, tokenName, owner, storedPat, state.newapiUserId());
                } catch (NewApiHttpException rejected) {
                    if (!rejected.isAuthenticationFailure()) {
                        throw rejected;
                    }
                    // A timeout or a 5xx says nothing about the PAT, and must not cost the user a
                    // password prompt; only an explicit rejection proves it is gone.
                }
            }

            String password = accountIdentity.loginPassword(user);
            NewApiLoginSession login;
            try {
                login = accountIdentity.ensureLogin(user);
            } catch (NewApiHttpException rejected) {
                // A rejected password means it was changed on the New API console. The way back is a
                // sign in here, which verifies and adopts it; retrying this call cannot help.
                if (rejected.isAuthenticationFailure()) {
                    throw new LlmInvocationException(
                            "New API rejected the stored password; sign in again on this site to "
                                    + "re-synchronise it", rejected);
                }
                throw rejected;
            }
            if (state.newapiUserId() != null
                    && state.newapiUserId().longValue() != login.userId()) {
                throw new LlmInvocationException(
                        "New API login returned a user that does not match the bound account");
            }
            if (state.newapiUserId() == null) {
                stateStore.bindNewApiUser(user.userId(), owner, login.userId());
            }
            long newapiUserId = login.userId();

            String pat = obtainPat(user, state, owner, login, password);
            return completeProvision(user, tokenName, owner, pat, newapiUserId);
        } catch (PasswordConfirmationRequiredException confirmationRequired) {
            /*
             * Not a failure: the caller has to supply one field and retry. Release the lease right
             * away (retryAt = now, no backoff) so that retry can claim immediately instead of being
             * answered with "still provisioning; retry shortly" — the same trap a backed-off
             * failure would set.
             */
            releaseLeaseImmediately(user.userId(), owner);
            throw confirmationRequired;
        } catch (RuntimeException exception) {
            recordFailure(user.userId(), owner, exception);
            if (exception instanceof ServiceUnavailableException unavailable) {
                throw unavailable;
            }
            if (exception instanceof LlmInvocationException invocation) {
                throw invocation;
            }
            throw new LlmInvocationException(
                    "Unable to provision the account relay token: " + safeError(exception), exception);
        }
    }

    /**
     * Finds (or creates) the relay token behind the given credential, reveals its key and stores it
     * encrypted. Split out of {@link #provision} so the no-login PAT path and the password path
     * finish identically.
     */
    private String completeProvision(CurrentUser user,
                                     String tokenName,
                                     String owner,
                                     String pat,
                                     long newapiUserId) {
        NewApiToken token = newApi.findExactToken(pat, tokenName, newapiUserId)
                .orElse(null);
        if (token == null) {
            newApi.createRelayToken(pat, tokenName);
            token = newApi.findExactToken(pat, tokenName, newapiUserId).orElseThrow(() ->
                    new LlmInvocationException(
                            "New API created the token but it could not be found by its exact name"));
        }
        if (!token.allowUserQuotaQuery()) {
            newApi.updateRelayToken(pat, token.id(), tokenName);
        }

        String relayKey = newApi.revealToken(pat, token.id());
        AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(relayKey);
        stateStore.complete(
                user.userId(),
                owner,
                token.id(),
                tokenName,
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.tag(),
                now());

        try {
            fetchAndSyncUsage(user.userId(), relayKey);
        } catch (RuntimeException usageFailure) {
            // Provisioning is complete. A temporary usage outage is surfaced by the next
            // credential check without discarding a valid encrypted relay key.
        }
        return relayKey;
    }

    private String obtainPat(CurrentUser user,
                             UserGatewayTokenStateStore.Snapshot state,
                             String owner,
                             NewApiLoginSession login,
                             String password) {
        if (hasEncryptedPat(state)) {
            String storedPat = cipher.decrypt(
                    state.patCiphertext(), state.patNonce(), state.patTag());
            try {
                newApi.validateAccessToken(storedPat);
            } catch (NewApiHttpException httpFailure) {
                if (!httpFailure.isAuthenticationFailure()) {
                    throw httpFailure;
                }
                // Only an explicit authentication rejection proves that the stored PAT is invalid.
                // Timeouts and 5xx responses must not cause credentials to be discarded.
                return reissuePat(user, owner, login, password);
            }
            return storedPat;
        }
        return reissuePat(user, owner, login, password);
    }

    private String reissuePat(CurrentUser user,
                              String owner,
                              NewApiLoginSession login,
                              String password) {
        String patName = patName(user);
        for (NewApiAccessToken accessToken : newApi.listAccessTokens(login.accessToken())) {
            if (patName.equals(accessToken.name())) {
                newApi.deleteAccessToken(login.accessToken(), password, accessToken.id());
            }
        }

        NewApiAccessTokenCredential credential = newApi.createAccessToken(
                login.accessToken(), password, patName, PAT_SCOPES);
        AesGcmTokenCipher.EncryptedToken encryptedPat = cipher.encrypt(credential.token());
        stateStore.bindPat(
                user.userId(),
                owner,
                credential.id(),
                encryptedPat.ciphertext(),
                encryptedPat.nonce(),
                encryptedPat.tag(),
                CURRENT_PAT_SCOPE_VERSION);
        return credential.token();
    }

    private String requireDetailedUsagePat(CurrentUser user) {
        requireRelayKey(user);
        UserGatewayTokenStateStore.Snapshot state = stateStore.find(user.userId())
                .orElseThrow(() -> new ServiceUnavailableException(
                        "The account usage credential is not available"));
        if (state.patScopeVersion() >= CURRENT_PAT_SCOPE_VERSION
                && hasEncryptedPat(state)) {
            return decryptPat(state);
        }

        String owner = UUID.randomUUID().toString();
        LocalDateTime now = now();
        LocalDateTime leaseUntil = now.plus(properties.getProvisioningLease());
        for (int tries = 0; tries < 3; tries++) {
            UserGatewayTokenStateStore.PatScopeUpgradeClaim claim =
                    stateStore.beginPatScopeUpgrade(
                            user.userId(),
                            owner,
                            CURRENT_PAT_SCOPE_VERSION,
                            now,
                            leaseUntil);
            UserGatewayTokenStateStore.PatScopeUpgradeStatus status = claim.status();
            if (status == UserGatewayTokenStateStore.PatScopeUpgradeStatus.COMPLETE) {
                return decryptPat(claim.token());
            }
            if (status == UserGatewayTokenStateStore.PatScopeUpgradeStatus.ACQUIRED) {
                return upgradePatScopes(user, claim.token(), owner);
            }
            if (status == UserGatewayTokenStateStore.PatScopeUpgradeStatus.BUSY) {
                throw new ServiceUnavailableException(
                        "The account usage credential is being upgraded; retry shortly");
            }
            if (status == UserGatewayTokenStateStore.PatScopeUpgradeStatus.NOT_READY) {
                throw new ServiceUnavailableException(
                        "The account usage credential is not ready");
            }
            throw new ServiceUnavailableException(
                    "The account usage credential returned an unknown status");
        }
        throw new ServiceUnavailableException(
                "The account usage credential could not be upgraded; retry shortly");
    }

    private String upgradePatScopes(CurrentUser user,
                                    UserGatewayTokenStateStore.Snapshot state,
                                    String owner) {
        try {
            if (state.newapiPatId() == null || !hasEncryptedPat(state)) {
                throw new ServiceUnavailableException(
                        "The account usage credential is not ready");
            }
            NewApiLoginSession login = accountIdentity.login(user);
            if (state.newapiUserId() == null
                    || login.userId() != state.newapiUserId().longValue()) {
                throw new LlmInvocationException(
                        "New API login returned a user that does not match the bound account");
            }
            String password = accountIdentity.loginPassword(user);
            try {
                newApi.updateAccessToken(
                        login.accessToken(),
                        password,
                        state.newapiPatId(),
                        patName(user),
                        PAT_SCOPES);
            } catch (NewApiHttpException exception) {
                if (exception.statusCode() != 404) {
                    throw exception;
                }
                String replacementPat = reissuePat(user, owner, login, password);
                stateStore.completePatScopeUpgrade(
                        user.userId(), owner, CURRENT_PAT_SCOPE_VERSION);
                return replacementPat;
            }
            stateStore.completePatScopeUpgrade(
                    user.userId(), owner, CURRENT_PAT_SCOPE_VERSION);
            return decryptPat(state);
        } catch (RuntimeException exception) {
            try {
                stateStore.abandonPatScopeUpgrade(user.userId(), owner);
            } catch (RuntimeException ignored) {
                // Keep the original error; a later request can retry the idempotent grant update.
            }
            throw exception;
        }
    }

    private NewApiTokenUsage fetchAndSyncUsage(Long userId, String relayKey) {
        try {
            NewApiTokenUsage usage = newApi.fetchUsage(relayKey);
            long userTotalGranted = userTotalGranted(usage);
            stateStore.updateUsage(
                    userId,
                    usage.modelLimits(),
                    userTotalGranted,
                    usage.userTotalUsed(),
                    usage.userTotalAvailable(),
                    usage.unlimitedQuota(),
                    usage.modelLimitsEnabled(),
                    usage.expiresAt(),
                    now());
            return usage;
        } catch (RuntimeException exception) {
            try {
                stateStore.usageFailed(userId, safeError(exception), now());
            } catch (RuntimeException ignored) {
                // Preserve the usage or balance error and do not mask it with a bookkeeping failure.
            }
            throw exception;
        }
    }

    private static long userTotalGranted(NewApiTokenUsage usage) {
        try {
            return Math.addExact(usage.userTotalUsed(), usage.userTotalAvailable());
        } catch (ArithmeticException exception) {
            throw new ServiceUnavailableException("New API user quota is invalid");
        }
    }

    /**
     * Hands the provisioning lease back without scheduling a backoff, so the retry that follows a
     * password confirmation can start at once.
     */
    private void releaseLeaseImmediately(Long userId, String owner) {
        try {
            stateStore.provisioningFailed(userId, owner, "password confirmation required", now());
        } catch (RuntimeException ignored) {
            // The lease may already have been lost; the caller still needs the 428.
        }
    }

    private void recordFailure(Long userId, String owner, RuntimeException exception) {
        try {
            stateStore.provisioningFailed(
                    userId,
                    owner,
                    safeError(exception),
                    now().plus(properties.getRetryDelay()));
        } catch (RuntimeException ignored) {
            // The lease may already have been lost to another worker. Never mask the provision error.
        }
    }

    private String decrypt(UserGatewayTokenStateStore.Snapshot token) {
        return cipher.decrypt(token.keyCiphertext(), token.keyNonce(), token.keyTag());
    }

    private String decryptPat(UserGatewayTokenStateStore.Snapshot token) {
        if (!hasEncryptedPat(token)) {
            throw new ServiceUnavailableException(
                    "The account usage credential is not available");
        }
        return cipher.decrypt(token.patCiphertext(), token.patNonce(), token.patTag());
    }

    private String tokenName(CurrentUser user) {
        if (user == null || user.userId() == null || user.siteId() == null) {
            throw new UnauthorizedException(ApiMessages.LOGIN_REQUIRED);
        }
        String name = "%s-%d-u%d".formatted(
                tokenPrefix(), user.siteId(), user.userId());
        if (name.length() > MAX_NEW_API_NAME_LENGTH || name.contains("%")) {
            throw new ServiceUnavailableException("New API token name is invalid");
        }
        return name;
    }

    private JsonNode readModelLimits(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
    }

    /**
     * The cached snapshot keeps {@code model_limits} as the raw JSON string that was stored in the
     * {@code LONGTEXT} column, so it has to be parsed back. A malformed row must not fail the whole
     * balance response: an empty limits object is a safer answer than a 500 on the wallet page.
     */
    private JsonNode readModelLimits(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
        } catch (Exception unparseable) {
            log.warn("Stored model_limits is not valid JSON, ignoring: {}", unparseable.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 6) + "..." + key.substring(key.length() - 4);
    }

    private static String requiredConfig(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ServiceUnavailableException(name + " is required for user billing");
        }
        return value.strip();
    }

    private String patName(CurrentUser user) {
        String name = "%s-pat-%d-u%d".formatted(
                tokenPrefix(), user.siteId(), user.userId());
        if (name.length() > MAX_NEW_API_NAME_LENGTH || name.contains("%")) {
            throw new ServiceUnavailableException("New API PAT name is invalid");
        }
        return name;
    }

    private String tokenPrefix() {
        String prefix = properties.getTokenNamePrefix() == null
                ? "hub"
                : properties.getTokenNamePrefix().strip();
        if (prefix.isBlank() || prefix.contains("%")) {
            throw new ServiceUnavailableException("New API token name prefix is invalid");
        }
        return prefix;
    }

    private static boolean hasEncryptedPat(UserGatewayTokenStateStore.Snapshot state) {
        return state.patCiphertext() != null && state.patCiphertext().length > 0
                && state.patNonce() != null && state.patTag() != null;
    }

    private NewApiBillingConfig billingConfig() {
        return new NewApiBillingConfig(
                properties.getQuotaPerUnit(),
                properties.getUsdExchangeRate());
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        String redacted = API_KEY_PATTERN.matcher(message).replaceAll("[redacted-key]");
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("$1[redacted]");
        redacted = redacted.replaceAll("\\s+", " ").strip();
        return redacted.length() <= MAX_ERROR_LENGTH
                ? redacted
                : redacted.substring(0, MAX_ERROR_LENGTH);
    }

    private static void pauseForConcurrentProvisioning() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException(
                    "Interrupted while waiting for account relay token provisioning");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
