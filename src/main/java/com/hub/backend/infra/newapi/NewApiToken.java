package com.hub.backend.infra.newapi;

public record NewApiToken(long id, long userId, String name, boolean allowUserQuotaQuery) {
}
