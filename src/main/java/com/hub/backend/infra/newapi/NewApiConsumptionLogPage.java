package com.hub.backend.infra.newapi;

import java.util.List;

public record NewApiConsumptionLogPage(
        int page,
        int pageSize,
        long total,
        List<NewApiConsumptionLog> items
) {
}
