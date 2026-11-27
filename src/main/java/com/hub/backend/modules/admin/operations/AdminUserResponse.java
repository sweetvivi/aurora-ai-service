package com.hub.backend.modules.admin.operations;

import com.hub.backend.modules.user.entity.UserAccountEntity;

import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String username,
        String nickname,
        String status,
        LocalDateTime createdAt,
        String remark) {

    public static AdminUserResponse from(UserAccountEntity account) {
        return new AdminUserResponse(
                account.getId(),
                valueOrEmpty(account.getUsername()),
                valueOrEmpty(account.getNickname()),
                UserAccountEntity.STATUS_ACTIVE.equalsIgnoreCase(account.getStatus())
                        ? "active"
                        : "disabled",
                account.getCreatedAt(),
                valueOrEmpty(account.getRemark()));
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
