package com.hub.backend.modules.gateway;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiToken;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.crypto.AesGcmTokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「用到某分组的模型时才建一把该分组的 Relay Token」。
 *
 * <p>为什么不能共用一把：New API 的分组是 Token 上的单值字段，模型是否可调用取决于该分组下
 * 有没有渠道。用户请求只在 {@code vip} 分组里有的模型时，用 {@code default} 的 Token 会得到
 * {@code No available channel for model ... under group default}。</p>
 *
 * <p>为什么不用上游的 {@code group=auto}：rc.47 虽然接受 {@code group=auto} + {@code auto_groups}，
 * 但Aurora那套部署在请求入口一律返回 {@code 403 No permission to access group auto}，
 * 而且 {@code auto_groups} 上限是 5，覆盖不了实际分组数量。见
 * {@code docs/new-api-billing.md} 5.1.1。</p>
 *
 * <p>令牌名由「前缀 + 站点 + 用户 + 分组哈希」决定，因此是幂等的：本地行丢了也能在上游按名字
 * 找回来，不会每次调用都新建一把。</p>
 */
@Service
public class NewApiGroupTokenService {

    private static final Logger log = LoggerFactory.getLogger(NewApiGroupTokenService.class);

    private static final int MAX_NEW_API_NAME_LENGTH = 50;
    private static final int GROUP_HASH_LENGTH = 8;
    private static final String RESERVED_AUTO_GROUP = "auto";
    private static final Duration USABLE_GROUP_CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_ERROR_LENGTH = 512;
    /** 抢租约时的行锁/唯一键冲突重试次数。 */
    private static final int MAX_CLAIM_CONFLICTS = 3;
    /** 别人正在开通时最多等多久（次数 × 间隔），够覆盖 5～6 次上游往返。 */
    private static final int MAX_BUSY_POLLS = 15;
    private static final long BUSY_POLL_INTERVAL_MILLIS = 200L;

    private final NewApiGroupTokenStateStore stateStore;
    private final NewApiManagementClient newApi;
    private final UserGatewayTokenService baseTokens;
    private final NewApiProperties properties;
    private final AesGcmTokenCipher cipher;
    private final Clock clock;

    /** 每个用户能用的分组名快照，避免每次懒建都去问一次 {@code /api/user/self/groups}。 */
    private final Map<Long, UsableGroups> usableGroupsCache = new ConcurrentHashMap<>();

    public NewApiGroupTokenService(NewApiGroupTokenStateStore stateStore,
                                   NewApiManagementClient newApi,
                                   UserGatewayTokenService baseTokens,
                                   NewApiProperties properties,
                                   AesGcmTokenCipher cipher,
                                   Clock clock) {
        this.stateStore = stateStore;
        this.newApi = newApi;
        this.baseTokens = baseTokens;
        this.properties = properties;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * 该用户在指定分组上的 Relay Key；第一次用到时现建。
     *
     * <p>失败语义与账号令牌一致：上游拒绝按 {@link LlmInvocationException} 抛出，
     * 正在开通中按 {@link ServiceUnavailableException} 让人重试，绝不回落到别的分组的钥匙 ——
     * 那会把请求悄悄发到错误的渠道上并记到另一份价格里。并发的首批请求会短暂等一下正在开通的
     * 那一个（它建好后直接复用），而不是立刻失败；上一次开通失败后的退避窗口则如实报出那次的原因。</p>
     */
    public String requireGroupKey(CurrentUser user, String groupKey) {
        String group = normalizeGroup(groupKey);
        String tokenName = tokenName(user, group);
        String owner = UUID.randomUUID().toString();

        int conflicts = 0;
        int busyPolls = 0;
        while (true) {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime leaseUntil = now.plus(properties.getProvisioningLease());
            NewApiGroupTokenStateStore.Claim claim;
            try {
                claim = stateStore.claim(
                        user.userId(), user.siteId(), group, tokenName, owner, now, leaseUntil);
            } catch (DataIntegrityViolationException | CannotAcquireLockException conflict) {
                conflicts += 1;
                if (conflicts > MAX_CLAIM_CONFLICTS) {
                    throw new ServiceUnavailableException(
                            "The relay token for group '" + group
                                    + "' could not be claimed; retry shortly");
                }
                pauseForConcurrentProvisioning();
                continue;
            }

            switch (claim.status()) {
                case READY -> {
                    return decrypt(claim.token());
                }
                case ACQUIRED -> {
                    return provision(user, group, tokenName, owner);
                }
                case BACKOFF -> throw backoffFailure(group, claim.token());
                case BUSY -> {
                    // 并发的首批请求：一个在真建，其余在这里等它建好后直接复用它的钥匙。
                    // 立刻 503 的话，出图页一次发几个请求就会有一半直接失败，而它们要的
                    // 明明是同一把钥匙。
                    busyPolls += 1;
                    if (busyPolls > MAX_BUSY_POLLS) {
                        throw new ServiceUnavailableException(
                                "The relay token for group '" + group
                                        + "' is still being provisioned; retry shortly");
                    }
                    sleepBeforeRepoll();
                }
            }
        }
    }

    /** 注销/清账时把本地密文全部废掉：远端令牌随账号一起消失，或已无法再从这里使用。 */
    public void invalidateAll(Long userId) {
        if (userId == null) {
            return;
        }
        usableGroupsCache.remove(userId);
        for (NewApiGroupTokenStateStore.Snapshot snapshot : stateStore.findAll(userId)) {
            try {
                stateStore.invalidate(userId, snapshot.groupKey());
            } catch (RuntimeException exception) {
                log.warn("Unable to invalidate group token for user {} (group {}): {}",
                        userId, snapshot.groupKey(), safeError(exception));
            }
        }
    }

    private String provision(CurrentUser user,
                             String group,
                             String tokenName,
                             String owner) {
        try {
            UserGatewayTokenService.RelayBase base = baseTokens.requireRelayBase(user);
            requireUsable(user, base.pat(), group);

            NewApiToken token = newApi.findExactToken(base.pat(), tokenName, base.newapiUserId())
                    .orElse(null);
            if (token == null) {
                newApi.createRelayToken(base.pat(), tokenName, group);
                token = newApi.findExactToken(base.pat(), tokenName, base.newapiUserId())
                        .orElseThrow(() -> new LlmInvocationException(
                                "New API created the group token but it could not be found by its "
                                        + "exact name"));
            } else if (!token.allowUserQuotaQuery()) {
                // 老行：名字对上但不是我们建的，补一次分组与查询开关。
                newApi.updateRelayToken(base.pat(), token.id(), tokenName, group);
            }

            String relayKey = newApi.revealToken(base.pat(), token.id());
            AesGcmTokenCipher.EncryptedToken encrypted = cipher.encrypt(relayKey);
            stateStore.complete(
                    user.userId(), group, owner, token.id(),
                    encrypted.ciphertext(), encrypted.nonce(), encrypted.tag());
            log.info("Provisioned relay token for group '{}' of user {}", group, user.userId());
            return relayKey;
        } catch (PasswordConfirmationRequiredException confirmationRequired) {
            // 不是失败：用户补一次密码后重试即可，别让退避把人挡在门外。
            releaseLease(user.userId(), group, owner);
            throw confirmationRequired;
        } catch (RuntimeException exception) {
            recordFailure(user.userId(), group, owner, exception);
            if (exception instanceof ServiceUnavailableException unavailable) {
                throw unavailable;
            }
            if (exception instanceof LlmInvocationException invocation) {
                throw invocation;
            }
            throw new LlmInvocationException(
                    "Unable to provision the relay token for group '" + group + "': "
                            + safeError(exception), exception);
        }
    }

    /**
     * 建之前先确认这个账号真的能用该分组。
     *
     * <p>rc.47 的 {@code POST /api/token/} 不校验非 auto 分组的可见性，一把越权的钥匙能建成功、
     * 直到调用时才 403。先问一次 {@code /api/user/self/groups} 就能把这种垃圾令牌挡在门外，
     * 并且给出一句能看懂的话。读取失败时放行 —— 上游抖动不该变成「你不能用这个模型」。</p>
     */
    private void requireUsable(CurrentUser user, String pat, String group) {
        List<String> usable = usableGroups(user, pat);
        if (usable == null || usable.isEmpty()) {
            return;
        }
        if (usable.stream().noneMatch(group::equals)) {
            throw new ServiceUnavailableException(
                    "The account is not allowed to use the upstream group '" + group + "'");
        }
    }

    private List<String> usableGroups(CurrentUser user, String pat) {
        UsableGroups cached = usableGroupsCache.get(user.userId());
        LocalDateTime now = LocalDateTime.now(clock);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.groups();
        }
        List<String> groups;
        try {
            groups = newApi.fetchUserGroupNames(pat);
        } catch (NewApiHttpException rejected) {
            if (rejected.statusCode() == 401) {
                // PAT 本身失效（被删/被换）：这不是「查不到」，继续往下只会在建令牌时再失败一次。
                throw rejected;
            }
            // 403 也可能是「PAT 缺 profile:read 权限」，而不是「这个分组不能用」。
            // 把它当成查不到放行：真正的越权会在上游建令牌或调用时暴露，比在这里
            // 变成永久 503（还被 30 秒退避反复掩盖）要好。PAT 已补上该权限（见
            // UserGatewayTokenService.PAT_SCOPES），这里只是兜住旧 PAT。
            log.debug("Unable to read usable groups for user {}: {}",
                    user.userId(), safeError(rejected));
            return cached == null ? List.of() : cached.groups();
        } catch (RuntimeException failure) {
            log.debug("Unable to read usable groups for user {}: {}",
                    user.userId(), safeError(failure));
            return cached == null ? List.of() : cached.groups();
        }
        usableGroupsCache.put(user.userId(),
                new UsableGroups(List.copyOf(groups), now.plus(USABLE_GROUP_CACHE_TTL)));
        return groups;
    }

    private void recordFailure(Long userId, String group, String owner, RuntimeException failure) {
        try {
            stateStore.failed(userId, group, owner, safeError(failure),
                    LocalDateTime.now(clock).plus(properties.getRetryDelay()));
        } catch (RuntimeException ignored) {
            // 租约可能已经被别人拿走，别把真正的开通错误盖掉。
        }
    }

    private void releaseLease(Long userId, String group, String owner) {
        try {
            stateStore.failed(userId, group, owner, null, LocalDateTime.now(clock));
        } catch (RuntimeException ignored) {
            // 同上。
        }
    }

    /**
     * 退避窗口内不再谎报「正在开通」。
     *
     * <p>这两个状态在旧实现里都报同一句 “is being provisioned”，后果是永久性的开通失败
     * （账号没有该分组、密码不在会话里、上游拒绝建令牌）被伪装成临时故障：前端提示「稍后重试」，
     * 用户就一直重试，日志里也看不到真正的原因。这里把上次记录的错误带出来。</p>
     */
    private ServiceUnavailableException backoffFailure(
            String group, NewApiGroupTokenStateStore.Snapshot snapshot) {
        String reason = snapshot.lastError() == null || snapshot.lastError().isBlank()
                ? "unknown error"
                : snapshot.lastError();
        log.warn("Relay token for group '{}' of user {} is waiting out a retry backoff: {}",
                group, snapshot.userId(), reason);
        return new ServiceUnavailableException(
                "The last attempt to provision the relay token for group '" + group
                        + "' failed: " + reason);
    }

    private String decrypt(NewApiGroupTokenStateStore.Snapshot snapshot) {
        if (!snapshot.hasKey()) {
            throw new ServiceUnavailableException(
                    "The stored relay token for group '" + snapshot.groupKey() + "' has no key");
        }
        return cipher.decrypt(
                snapshot.keyCiphertext(), snapshot.keyNonce(), snapshot.keyTag());
    }

    /**
     * New API 令牌名：{@code 前缀-站点-u用户-g分组哈希}。
     *
     * <p>分组名可能是中文且最长 64 字符，直接拼进去会超 New API 的 50 字符上限；
     * 用分组名的 SHA-256 前 8 位做后缀，既定长又稳定 —— 同一分组永远得到同一个名字，
     * 于是按名字就能找回上游那把钥匙。</p>
     */
    private String tokenName(CurrentUser user, String group) {
        if (user == null || user.userId() == null || user.siteId() == null) {
            throw new ServiceUnavailableException("A signed-in user is required to provision a token");
        }
        String name = "%s-%d-u%d-g%s".formatted(
                baseTokens.relayTokenPrefix(), user.siteId(), user.userId(), groupHash(group));
        if (name.length() > MAX_NEW_API_NAME_LENGTH || name.contains("%")) {
            throw new ServiceUnavailableException("New API token name is invalid");
        }
        return name;
    }

    private static String groupHash(String group) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(group.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, GROUP_HASH_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String normalizeGroup(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) {
            throw new ServiceUnavailableException("A relay group is required");
        }
        String group = groupKey.strip();
        if (RESERVED_AUTO_GROUP.equals(group.toLowerCase(Locale.ROOT))) {
            throw new ServiceUnavailableException(
                    "The reserved 'auto' group cannot be provisioned; use a concrete group name");
        }
        if (group.length() > 64) {
            throw new ServiceUnavailableException("Relay group name is too long");
        }
        return group;
    }

    private static void pauseForConcurrentProvisioning() {
        try {
            Thread.sleep(15L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等待另一个请求把钥匙建好；比抢租约的 15ms 长得多，因为它期间在打上游。 */
    private static void sleepBeforeRepoll() {
        try {
            Thread.sleep(BUSY_POLL_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeError(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure == null ? "unknown error" : failure.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private record UsableGroups(List<String> groups, LocalDateTime expiresAt) {
    }
}
