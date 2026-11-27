package com.hub.backend.modules.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

public record TokenUsageResponse(
        String maskedKey,
        String tokenName,
        long totalGranted,
        long totalUsed,
        long totalAvailable,
        String currency,
        BigDecimal totalGrantedCny,
        BigDecimal totalUsedCny,
        BigDecimal totalAvailableCny,
        /**
         * Quota units per 1 USD, echoed from {@code NEW_API_QUOTA_PER_UNIT}.
         *
         * <p>New API itself renders a balance as {@code quota / quota_per_unit} USD, so the client
         * needs this divisor to reproduce that number instead of guessing it. Sent with every
         * snapshot so a deployment that changes the unit does not need a front-end release.</p>
         */
        BigDecimal quotaPerUnit,
        /** USD→CNY rate, echoed from {@code NEW_API_USD_EXCHANGE_RATE}. */
        BigDecimal usdExchangeRate,
        boolean unlimitedQuota,
        JsonNode modelLimits,
        boolean modelLimitsEnabled,
        Instant expiresAt,
        Instant lastSyncedAt
) {
}
