package com.hub.backend.modules.redemption.dto;

import java.math.BigDecimal;
import java.util.List;

public record UserRedemptionOptionsResponse(
        List<BigDecimal> commonAmounts
) {
}
