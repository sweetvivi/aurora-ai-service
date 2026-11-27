package com.hub.backend.common.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * New API per-user relay and anonymous public catalogue settings.
 *
 * <p>User-specific operations use only the encrypted credentials owned by that user. The single
 * exception is {@link #adminUsername}: freeing a username after an account is deleted cannot be
 * done with the user's own credentials, because New API soft-deletes the row and then treats the
 * name as taken forever. Left empty, nothing changes and the name simply stays reserved.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.new-api")
public class NewApiProperties {

    /**
     * Whether user AI traffic is routed through New API with a per-user relay key.
     *
     * <p>When off, the static {@code app.llm.api-key} is used as a local development escape hatch.
     * Production deployments that bill per account keep this enabled.</p>
     */
    private boolean enabled = true;

    /** New API root (no {@code /v1}). Must be supplied via {@code NEW_API_BASE_URL}; no repository default. */
    private String baseUrl = "";

    /** Base64 encoded 32-byte AES key used to encrypt PATs and relay keys at rest. */
    private String encryptionKey = "";

    private String tokenNamePrefix = "hub";

    private String group = "default";

    /**
     * Optional read-only account used solely to read the model plaza when it is not public.
     *
     * <p>New API gates {@code GET /api/pricing} behind the {@code pricing} header-nav module. When
     * that module is configured with {@code requireAuth}, the endpoint answers 401 to anonymous
     * callers and the catalogue sync would stop refreshing. Setting these two values lets the sync
     * sign in as its own account and send {@code Authorization: Bearer}; leave them blank to keep
     * the anonymous request.</p>
     *
     * <p>This account's own group decides which models come back: New API filters the plaza by the
     * caller's usable groups, so a sync account in {@code default} cannot see a premium-only model.
     * Use an account whose group (or usable-group grants) covers every group the site should
     * publish, or run one sync per group.</p>
     *
     * <p>The password has to stay in plaintext here because the only way to obtain a session token
     * is New API's own password sign-in. It is never logged.</p>
     */
    private String catalogUsername = "";

    /** Password of {@link #catalogUsername}. */
    private String catalogPassword = "";

    /**
     * Optional New API administrator account, used only to remove an account permanently.
     *
     * <p>New API marks a self-deleted user as deleted instead of removing the row, and then refuses
     * that username forever: {@code model.CheckUserExistOrDeleted} answers "Username already exists
     * or has been deleted", so the owner can never register the name again. The administrative
     * endpoint {@code DELETE /api/user/{id}} is the only way back, and it needs an administrator.
     * </p>
     *
     * <p>Leave both blank to keep the default: the remote row stays, the username stays reserved,
     * and account deletion itself still works. Set {@code NEW_API_ADMIN_USERNAME} and
     * {@code NEW_API_ADMIN_PASSWORD} to free the name on deletion; the account must have the
     * administrator role upstream, and the password has to stay in plaintext here because New API
     * asks for a password proof on that endpoint.</p>
     */
    private String adminUsername = "";

    /** Password of {@link #adminUsername}. Never logged; only sent to New API's own endpoints. */
    private String adminPassword = "";

    private Duration loginTimeout = Duration.ofSeconds(15);

    private Duration userAccessTokenTimeout = Duration.ofSeconds(15);

    private Duration tokenManagementTimeout = Duration.ofSeconds(15);

    private Duration catalogTimeout = Duration.ofSeconds(15);

    /** Whether the local provider/model cache is refreshed when the application starts. */
    private boolean catalogSyncOnStartup = true;

    /** Whether the local provider/model cache is refreshed periodically. */
    private boolean catalogSyncEnabled = true;

    /** Delay between completed catalogue syncs. */
    private long catalogSyncIntervalMillis = 300_000;

    /** Delay before the first periodic sync, keeping it clear of the startup refresh. */
    private long catalogSyncInitialDelayMillis = 300_000;

    private Duration usageTimeout = Duration.ofSeconds(15);

    /** New API quota units per US dollar, used to convert user usage snapshots. */
    @Positive
    private BigDecimal quotaPerUnit = new BigDecimal("500000");

    /** USD/CNY exchange rate used when displaying wallet amounts. */
    @Positive
    private BigDecimal usdExchangeRate = new BigDecimal("7.3");

    /**
     * How long a persisted usage snapshot is trusted before New API is queried again.
     *
     * <p>Zero or negative disables the cache.</p>
     */
    private Duration usageCacheTtl = Duration.ofMinutes(3);

    /**
     * Minimum age a snapshot must have before {@code ?refresh=true} may bypass
     * {@link #usageCacheTtl}.
     *
     * <p>The explicit refresh exists because a charge or a refund has to show up on the account
     * page right away, while the 3-minute TTL keeps every AI call off New API's critical limiter.
     * The floor keeps a page that refreshes in a loop from turning that escape hatch into an
     * unthrottled path — the limiter is 20 requests per 20 minutes per IP.</p>
     */
    private Duration usageForceRefreshInterval = Duration.ofSeconds(10);

    private Duration provisioningLease = Duration.ofMinutes(2);

    private Duration retryDelay = Duration.ofSeconds(30);

    /**
     * 注销时远端删除被限流（HTTP 429）之后，是否把它排成后台任务继续删。
     *
     * <p>关掉就回到旧行为：本地注销照样成功，远端账号留在上游，只留一行 warn。开着才有
     * 「等限流过去接着删」这件事。默认开 —— 撞限流是批量注销的常态，不是异常。</p>
     */
    private boolean deletionRetryEnabled = true;

    /** 后台扫描待办删除任务的间隔（fixedDelay，跑完这一轮再等）。 */
    private long deletionSweepIntervalMillis = 30_000L;

    /** 一次扫描最多认领几条。太小会让一批账号排队等好几轮，太大会把限流窗口重新打满。 */
    private int deletionSweepBatchSize = 20;

    /**
     * 第一次重试的等待时间。
     *
     * <p>New API 的关键限流是 20 次 / 20 分钟，429 之后窗口通常还没过去，所以不要立刻重试：
     * 立即重试只会再吃一个 429，把「重试次数」白白耗掉。</p>
     */
    private Duration deletionRetryInitialDelay = Duration.ofMinutes(1);

    /** 重试间隔按 2 的幂增长后封顶在这里。默认等于限流窗口的整长。 */
    private Duration deletionRetryMaxDelay = Duration.ofMinutes(20);

    /**
     * 最多试几次，超过就标 FAILED 留给人工。
     *
     * <p>按默认间隔算，1 + 2 + 4 + 8 + 16 + 20×5 ≈ 2 小时，足够熬过持续被压的限流；
     * 再久就不是「等窗口」而是「上游坏了」，该有人看一眼。</p>
     */
    private int deletionRetryMaxAttempts = 10;

    /** 一次认领的租约长度。实例中途挂掉，租约过期后别的实例能接手。 */
    private Duration deletionTaskLease = Duration.ofMinutes(5);

    /**
     * Explicit model-key → type overrides applied during New API catalogue sync.
     *
     * <p>Keys are matched case-insensitively against the plaza model name. Values must be one of
     * {@code CHAT}, {@code IMAGE}, {@code VIDEO}, {@code AUDIO}. When present, an override wins
     * over keyword heuristics and also overwrites an existing row's {@code model_type} on refresh
     * — use this for names that heuristics cannot classify reliably.</p>
     */
    private Map<String, String> modelTypeOverrides = new LinkedHashMap<>();

    public String resolvedBaseUrl() {
        String trimmed = baseUrl == null ? "" : baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getTokenNamePrefix() {
        return tokenNamePrefix;
    }

    public void setTokenNamePrefix(String tokenNamePrefix) {
        this.tokenNamePrefix = tokenNamePrefix;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public Duration getLoginTimeout() {
        return loginTimeout;
    }

    public void setLoginTimeout(Duration loginTimeout) {
        this.loginTimeout = loginTimeout;
    }

    public Duration getUserAccessTokenTimeout() {
        return userAccessTokenTimeout;
    }

    public void setUserAccessTokenTimeout(Duration userAccessTokenTimeout) {
        this.userAccessTokenTimeout = userAccessTokenTimeout;
    }

    public Duration getTokenManagementTimeout() {
        return tokenManagementTimeout;
    }

    public void setTokenManagementTimeout(Duration tokenManagementTimeout) {
        this.tokenManagementTimeout = tokenManagementTimeout;
    }

    public Duration getCatalogTimeout() {
        return catalogTimeout;
    }

    public void setCatalogTimeout(Duration catalogTimeout) {
        this.catalogTimeout = catalogTimeout;
    }

    public boolean isCatalogSyncOnStartup() {
        return catalogSyncOnStartup;
    }

    public void setCatalogSyncOnStartup(boolean catalogSyncOnStartup) {
        this.catalogSyncOnStartup = catalogSyncOnStartup;
    }

    public boolean isCatalogSyncEnabled() {
        return catalogSyncEnabled;
    }

    public void setCatalogSyncEnabled(boolean catalogSyncEnabled) {
        this.catalogSyncEnabled = catalogSyncEnabled;
    }

    public long getCatalogSyncIntervalMillis() {
        return catalogSyncIntervalMillis;
    }

    public void setCatalogSyncIntervalMillis(long catalogSyncIntervalMillis) {
        this.catalogSyncIntervalMillis = catalogSyncIntervalMillis;
    }

    public long getCatalogSyncInitialDelayMillis() {
        return catalogSyncInitialDelayMillis;
    }

    public void setCatalogSyncInitialDelayMillis(long catalogSyncInitialDelayMillis) {
        this.catalogSyncInitialDelayMillis = catalogSyncInitialDelayMillis;
    }

    public Duration getUsageTimeout() {
        return usageTimeout;
    }

    public void setUsageTimeout(Duration usageTimeout) {
        this.usageTimeout = usageTimeout;
    }

    public BigDecimal getQuotaPerUnit() {
        return quotaPerUnit;
    }

    public void setQuotaPerUnit(BigDecimal quotaPerUnit) {
        this.quotaPerUnit = quotaPerUnit;
    }

    public BigDecimal getUsdExchangeRate() {
        return usdExchangeRate;
    }

    public void setUsdExchangeRate(BigDecimal usdExchangeRate) {
        this.usdExchangeRate = usdExchangeRate;
    }

    public Duration getUsageCacheTtl() {
        return usageCacheTtl;
    }

    public void setUsageCacheTtl(Duration usageCacheTtl) {
        this.usageCacheTtl = usageCacheTtl;
    }

    public Duration getUsageForceRefreshInterval() {
        return usageForceRefreshInterval;
    }

    public void setUsageForceRefreshInterval(Duration usageForceRefreshInterval) {
        this.usageForceRefreshInterval = usageForceRefreshInterval;
    }

    public Duration getProvisioningLease() {
        return provisioningLease;
    }

    public void setProvisioningLease(Duration provisioningLease) {
        this.provisioningLease = provisioningLease;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Map<String, String> getModelTypeOverrides() {
        return modelTypeOverrides;
    }

    public void setModelTypeOverrides(Map<String, String> modelTypeOverrides) {
        this.modelTypeOverrides = modelTypeOverrides == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(modelTypeOverrides);
    }

    public String getCatalogUsername() {
        return catalogUsername;
    }

    public void setCatalogUsername(String catalogUsername) {
        this.catalogUsername = catalogUsername == null ? "" : catalogUsername.strip();
    }

    public String getCatalogPassword() {
        return catalogPassword;
    }

    public void setCatalogPassword(String catalogPassword) {
        this.catalogPassword = catalogPassword == null ? "" : catalogPassword;
    }

    /**
     * Whether the catalogue sync can sign in, so it may read a plaza that requires authentication.
     */
    public boolean hasCatalogCredentials() {
        return !catalogUsername.isBlank() && !catalogPassword.isBlank();
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername == null ? "" : adminUsername.strip();
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword == null ? "" : adminPassword;
    }

    /** Whether a New API administrator is configured, so deletion can free the username. */
    public boolean hasAdminCredentials() {
        return !adminUsername.isBlank() && !adminPassword.isBlank();
    }

    public boolean isDeletionRetryEnabled() {
        return deletionRetryEnabled;
    }

    public void setDeletionRetryEnabled(boolean deletionRetryEnabled) {
        this.deletionRetryEnabled = deletionRetryEnabled;
    }

    public long getDeletionSweepIntervalMillis() {
        return deletionSweepIntervalMillis;
    }

    public void setDeletionSweepIntervalMillis(long deletionSweepIntervalMillis) {
        this.deletionSweepIntervalMillis = deletionSweepIntervalMillis;
    }

    public int getDeletionSweepBatchSize() {
        return deletionSweepBatchSize;
    }

    public void setDeletionSweepBatchSize(int deletionSweepBatchSize) {
        this.deletionSweepBatchSize = deletionSweepBatchSize;
    }

    public Duration getDeletionRetryInitialDelay() {
        return deletionRetryInitialDelay;
    }

    public void setDeletionRetryInitialDelay(Duration deletionRetryInitialDelay) {
        this.deletionRetryInitialDelay = deletionRetryInitialDelay;
    }

    public Duration getDeletionRetryMaxDelay() {
        return deletionRetryMaxDelay;
    }

    public void setDeletionRetryMaxDelay(Duration deletionRetryMaxDelay) {
        this.deletionRetryMaxDelay = deletionRetryMaxDelay;
    }

    public int getDeletionRetryMaxAttempts() {
        return deletionRetryMaxAttempts;
    }

    public void setDeletionRetryMaxAttempts(int deletionRetryMaxAttempts) {
        this.deletionRetryMaxAttempts = deletionRetryMaxAttempts;
    }

    public Duration getDeletionTaskLease() {
        return deletionTaskLease;
    }

    public void setDeletionTaskLease(Duration deletionTaskLease) {
        this.deletionTaskLease = deletionTaskLease;
    }

    /**
     * Looks up a configured type override for {@code modelKey}.
     *
     * @return normalised upper-case type, or {@code null} when no override is configured
     */
    public String modelTypeOverride(String modelKey) {
        if (modelKey == null || modelKey.isBlank() || modelTypeOverrides.isEmpty()) {
            return null;
        }
        String needle = modelKey.strip();
        for (Map.Entry<String, String> entry : modelTypeOverrides.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (needle.equalsIgnoreCase(entry.getKey().strip())) {
                String type = entry.getValue().strip().toUpperCase(Locale.ROOT);
                return type.isEmpty() ? null : type;
            }
        }
        return null;
    }
}
