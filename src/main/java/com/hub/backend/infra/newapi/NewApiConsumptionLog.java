package com.hub.backend.infra.newapi;

/**
 * One row of New API's per-user usage log.
 *
 * <p>{@code type} is New API's {@code LogType}: 1 topup, 2 consume, 6 refund, plus a few
 * operational kinds (system / error / manage). Refunds matter here — a failed async task
 * (video, task relay) is settled by writing a refund row, and the account page has to show it,
 * otherwise the money simply disappears from the user's point of view.</p>
 *
 * <p>{@code quota} is always the absolute amount; the direction comes from {@code type}.</p>
 */
public record NewApiConsumptionLog(
        long id,
        int type,
        long createdAt,
        String modelName,
        String tokenName,
        long quota,
        long promptTokens,
        long completionTokens,
        long useTime,
        boolean stream
) {
}
