package com.hub.backend.modules.redemption.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record UserRedemptionResponse(
        long quota,
        BigDecimal amountCny,
        String maskedCode,
        Instant redeemedAt
) {
}
