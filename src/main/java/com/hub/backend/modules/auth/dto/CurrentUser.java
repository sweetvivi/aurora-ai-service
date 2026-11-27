package com.hub.backend.modules.auth.dto;

import com.hub.backend.modules.user.entity.UserAccountEntity;

/**
 * The account a request is executed on behalf of.
 *
 * @param userId   account id
 * @param siteId   owning site
 * @param username account name, may be {@code null} for third party only accounts
 */
public record CurrentUser(Long userId, Long siteId, String username) {

    public static CurrentUser of(UserAccountEntity account) {
        return new CurrentUser(account.getId(), account.getSiteId(), account.getUsername());
    }
}
