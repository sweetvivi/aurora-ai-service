package com.hub.backend.modules.gateway;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给审计注解用的「余额 / 令牌」观测点。
 *
 * <p>存在的唯一原因：{@code @AuditLog} 的 {@code before}/{@code after} 是 SpEL 表达式，
 * 表达式需要有个 bean 能读到变更前后的值。把读取逻辑放在这里而不是散在业务方法里，
 * 业务代码才不需要为了审计多写一行。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * @AuditLog(action = "REDEEM_CODE", target = "USER_BALANCE", id = "#user.userId()",
 *           before = "@auditProbes.balance(#user.userId())",
 *           after  = "@auditProbes.balance(#user.userId())")
 * }</pre>
 *
 * <p>读取的是**本地已经落库的额度快照**（{@code user_gateway_tokens}），不是现打 New API。
 * 这很重要：审计切面每次调用都会跑两遍表达式，如果每次都去上游查余额，
 * 一个兑换请求就会多打两次上游，而 New API 的 usage 接口只有 20 次/20 分钟的限额。
 * 快照本来就是「最后一次从上游对账的结果」，兑换流程内部会刷新它，因此 before/after 恰好
 * 对应「充值前」和「充值后」。</p>
 *
 * <p>只输出额度数字与时间戳，不含密钥密文：审计表不需要、也不应该持有任何可以解密的材料。</p>
 */
@Component("auditProbes")
public class AuditProbes {

    private final UserGatewayTokenStateStore stateStore;

    public AuditProbes(UserGatewayTokenStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 用户额度快照：{@code granted}（累计获得）、{@code used}（累计消耗）、
     * {@code available}（可用）、{@code unlimited}、{@code snapshotAt}（最后一次对账时间）。
     *
     * <p>没有快照时返回 {@code null} 而不是空 Map：审计里 null 表示「当时还没有这个用户的状态」，
     * 与「额度为 0」是两件事，混在一起会让对账结论出错。</p>
     */
    public Map<String, Object> balance(Long userId) {
        if (userId == null) {
            return null;
        }
        return stateStore.find(userId).map(snapshot -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("granted", snapshot.totalGranted());
            values.put("used", snapshot.totalUsed());
            values.put("available", snapshot.totalAvailable());
            values.put("unlimited", snapshot.unlimitedQuota());
            values.put("snapshotAt", snapshot.lastSyncAt());
            return values;
        }).orElse(null);
    }

    /**
     * 中转令牌状态：名称、上游 id、状态机状态与是否已下发密钥（只记布尔，不记密钥）。
     * 用于「令牌被重建/挂起/吊销」这类变更的审计。
     */
    public Map<String, Object> token(Long userId) {
        if (userId == null) {
            return null;
        }
        return stateStore.find(userId).map(snapshot -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("tokenName", snapshot.tokenName());
            values.put("newapiTokenId", snapshot.newapiTokenId());
            values.put("status", snapshot.status());
            values.put("hasKey", snapshot.keyCiphertext() != null && snapshot.keyCiphertext().length > 0);
            values.put("modelLimitsEnabled", snapshot.modelLimitsEnabled());
            values.put("expiresAt", snapshot.expiresAt());
            return values;
        }).orElse(null);
    }
}
