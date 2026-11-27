package com.hub.backend.infra.newapi;

import java.math.BigDecimal;

public record NewApiBillingConfig(
        BigDecimal quotaPerUnit,
        BigDecimal usdExchangeRate
) {
}
