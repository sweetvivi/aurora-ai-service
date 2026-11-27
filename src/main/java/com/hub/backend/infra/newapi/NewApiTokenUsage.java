package com.hub.backend.infra.newapi;

import com.fasterxml.jackson.databind.JsonNode;

public record NewApiTokenUsage(
        String name,
        long tokenTotalGranted,
        long tokenTotalUsed,
        long tokenTotalAvailable,
        boolean unlimitedQuota,
        JsonNode modelLimits,
        boolean modelLimitsEnabled,
        long expiresAt,
        long userTotalUsed,
        long userTotalAvailable
) {
}
