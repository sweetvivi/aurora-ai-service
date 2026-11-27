package com.hub.backend.modules.auth;

import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.ForbiddenException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.user.UserService;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Restores the plaintext password of an in-flight session after the vault lost it.
 *
 * <p>{@link SessionPasswordVault} keeps the password in memory only, so a restart, a 30-day TTL or
 * a {@code MAX_ENTRIES} eviction empties it while the access token stays valid. Until now the only
 * way back was signing in again, which is a whole extra login for what is really one missing field
 * — and it is the only reason a signed-in user could not redeem a code after a deploy.</p>
 *
 * <p>The candidate is verified against the stored Argon2id hash, exactly like a sign in, so holding
 * a valid access token is not by itself enough to fill the vault. Nothing is persisted: a verified
 * password goes straight back into memory with a fresh TTL.</p>
 */
@Service
public class SessionPasswordConfirmationService {

    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final SessionPasswordVault passwords;

    public SessionPasswordConfirmationService(UserService users,
                                             PasswordEncoder passwordEncoder,
                                             SessionPasswordVault passwords) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwords = passwords;
    }

    /**
     * Verifies the account password and remembers it for the rest of the session.
     *
     * @throws PasswordConfirmationRequiredException when no candidate was supplied, which is the
     *         signal that lets a client show a password prompt and retry the same request
     * @throws InvalidRequestException when the candidate does not match the stored hash
     */
    public void confirm(CurrentUser user, String candidate) {
        if (user == null || user.userId() == null) {
            throw new UnauthorizedException(ApiMessages.LOGIN_REQUIRED);
        }
        if (candidate == null || candidate.isBlank()) {
            throw new PasswordConfirmationRequiredException(ApiMessages.PASSWORD_CONFIRM_REQUIRED);
        }
        // Bound the work an unauthenticated-length string can cause inside the hash comparison.
        int length = candidate.codePointCount(0, candidate.length());
        if (length > PasswordPolicy.MAX_CODE_POINTS) {
            throw new InvalidRequestException(ApiMessages.BAD_REQUEST);
        }

        UserAccountEntity account = users.requireById(user.userId());
        if (!account.isActive()) {
            throw new ForbiddenException(ApiMessages.ACCOUNT_DISABLED);
        }
        String hash = account.getPasswordHash();
        if (hash == null || !passwordEncoder.matches(candidate, hash)) {
            throw new InvalidRequestException(ApiMessages.CURRENT_PASSWORD_INCORRECT);
        }
        passwords.remember(user.userId(), candidate);
    }
}
