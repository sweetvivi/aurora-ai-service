package com.hub.backend.modules.redemption;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiHttpException;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.modules.auth.SessionPasswordConfirmationService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.NewApiAccountIdentityService;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.redemption.dto.UserRedemptionOptionsResponse;
import com.hub.backend.modules.redemption.dto.UserRedemptionResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** User-owned redemption flow. No management credential is involved. */
@Service
public class RedemptionService {

    private static final List<BigDecimal> COMMON_AMOUNTS = List.of(
            new BigDecimal("10"),
            new BigDecimal("30"),
            new BigDecimal("50"),
            new BigDecimal("100"),
            new BigDecimal("300"));

    private final NewApiManagementClient newApi;
    private final NewApiAccountIdentityService accountIdentity;
    private final UserGatewayTokenService gatewayTokens;
    private final SessionPasswordVault passwords;
    private final SessionPasswordConfirmationService passwordConfirmation;
    private final NewApiProperties properties;
    private final Clock clock;

    public RedemptionService(NewApiManagementClient newApi,
                             NewApiAccountIdentityService accountIdentity,
                             UserGatewayTokenService gatewayTokens,
                             SessionPasswordVault passwords,
                             SessionPasswordConfirmationService passwordConfirmation,
                             NewApiProperties properties,
                             Clock clock) {
        this.newApi = newApi;
        this.accountIdentity = accountIdentity;
        this.gatewayTokens = gatewayTokens;
        this.passwords = passwords;
        this.passwordConfirmation = passwordConfirmation;
        this.properties = properties;
        this.clock = clock;
    }

    public UserRedemptionOptionsResponse userOptions() {
        return new UserRedemptionOptionsResponse(COMMON_AMOUNTS);
    }

    /**
     * 兑换码充值：这是全站唯一会「凭空增加余额」的入口，必须留审计。
     *
     * <p>before/after 都读本地额度快照：{@code redeem} 内部会调
     * {@code gatewayTokens.refreshUsageSnapshot} 把上游余额同步下来，所以方法返回后读到的
     * 就是「充值后」的值。用同一个表达式，切面就能算出可用额度差了多少。</p>
     *
     * <p>{@code recordArgs = false}：入参里有兑换码原文和明文密码，前者等同于现金，
     * 后者是用户凭证，都不能进审计表。需要知道是哪张码时用返回的 maskCode。</p>
     */
    @AuditLog(action = "REDEEM_CODE", target = "USER_BALANCE", id = "#user.userId()",
            before = "@auditProbes.balance(#user.userId())",
            after = "@auditProbes.balance(#user.userId())",
            recordArgs = false)
    public UserRedemptionResponse redeem(CurrentUser user, String rawCode, String rawPassword) {
        String code = rawCode == null ? "" : rawCode.strip();
        if (code.length() < 8 || code.length() > 128) {
            throw new InvalidRequestException("请输入有效的兑换码");
        }

        requireSessionPassword(user, rawPassword);

        NewApiLoginSession login = accountIdentity.ensureLogin(user);
        long quota;
        try {
            quota = newApi.redeemRedemption(login.accessToken(), code);
        } catch (NewApiHttpException exception) {
            if (exception.statusCode() == 400 || exception.statusCode() == 429) {
                throw invalidRedemption();
            }
            throw exception;
        } catch (LlmInvocationException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("New API rejected the request")) {
                throw invalidRedemption();
            }
            throw exception;
        }

        // A redemption credits the New API user, not the relay token's cached quota snapshot.
        gatewayTokens.refreshUsageSnapshot(user);
        return new UserRedemptionResponse(
                quota,
                quotaAmountCny(quota),
                maskCode(code),
                Instant.now(clock));
    }

    /**
     * Makes sure this request can sign in to New API as the user.
     *
     * <p>That sign-in needs the plaintext password, which the server keeps in memory only. When the
     * entry is gone — process restart, 30-day TTL, eviction — the caller is asked for the password
     * once (HTTP 428) instead of being told to sign in again: the token is still valid, so one extra
     * field is all this request needs. A verified password is written back with a fresh TTL, so the
     * same prompt does not come back for the next redemption, or for an AI call that has to
     * re-provision the relay token.</p>
     */
    private void requireSessionPassword(CurrentUser user, String rawPassword) {
        if (user != null && user.userId() != null && passwords.password(user.userId()).isPresent()) {
            return;
        }
        passwordConfirmation.confirm(user, rawPassword);
    }

    private BigDecimal quotaAmountCny(long quota) {
        if (quota <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(quota)
                .divide(properties.getQuotaPerUnit(), 16, RoundingMode.HALF_UP)
                .multiply(properties.getUsdExchangeRate())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String maskCode(String code) {
        if (code == null || code.isBlank()) {
            return "****";
        }
        String stripped = code.strip();
        if (stripped.length() <= 8) {
            return "****";
        }
        return stripped.substring(0, 4)
                + "****"
                + stripped.substring(stripped.length() - 4);
    }

    private static InvalidRequestException invalidRedemption() {
        return new InvalidRequestException("兑换码无效、已使用或已过期");
    }
}
