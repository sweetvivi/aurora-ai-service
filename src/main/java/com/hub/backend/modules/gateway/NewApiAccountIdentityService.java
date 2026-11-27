package com.hub.backend.modules.gateway;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.security.SessionPasswordVault;
import com.hub.backend.infra.newapi.NewApiLoginSession;
import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.entity.NewApiAccountEntity;
import com.hub.backend.modules.gateway.repository.NewApiAccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Owns the mapping between a local account and the user's New API identity.
 *
 * <p>No global management credential exists here. A binding is created only after the user's own
 * username and password have opened the New API account. The password itself is never stored: it is
 * held in {@link SessionPasswordVault} for the life of the session, because the PAT that opens
 * relay access has to be created with the same credentials.</p>
 */
@Service
public class NewApiAccountIdentityService {

    private final NewApiManagementClient newApi;
    private final NewApiProperties properties;
    private final NewApiAccountRepository accounts;
    private final SessionPasswordVault passwords;

    public NewApiAccountIdentityService(NewApiManagementClient newApi,
                                        NewApiProperties properties,
                                        NewApiAccountRepository accounts,
                                        SessionPasswordVault passwords) {
        this.newApi = newApi;
        this.properties = properties;
        this.accounts = accounts;
        this.passwords = passwords;
    }

    /**
     * Signs in as the current user with the password held for this session and verifies the remote
     * id. The first successful sign-in establishes the binding; later sign-ins must match it.
     */
    public NewApiLoginSession ensureLogin(CurrentUser user) {
        validateUser(user);
        NewApiLoginSession login = login(user);
        Optional<Long> bound = boundNewApiUserId(user.userId());
        if (bound.isPresent() && !bound.get().equals(login.userId())) {
            throw new LlmInvocationException(
                    "New API login returned a user that does not match the bound account");
        }
        if (bound.isEmpty()) {
            bindIdentity(user.userId(), user.siteId(), user.username(), login.userId(), true);
        }
        return login;
    }

    /** Signs in as the user with the password copy stored by a local registration or sign-in. */
    public NewApiLoginSession login(CurrentUser user) {
        validateUser(user);
        requireEnabled();
        return newApi.login(requireUsername(user.username()), loginPassword(user));
    }

    /** Signs in with a user-supplied candidate when a local account does not exist yet. */
    public NewApiLoginSession loginWithCandidate(String username, String candidate) {
        requireEnabled();
        return newApi.login(requireUsername(username), candidate);
    }

    /**
     * Creates the New API account through the public registration endpoint and signs it in.
     *
     * <p>No administrator is involved: this is the same endpoint the New API sign-up page uses, so
     * whatever gates registration there (closed registration, email verification) also gates this
     * call. Signing in immediately afterwards is what turns the registration into a stable user id,
     * which is the only proof of ownership this site can record.</p>
     *
     * <p>When the upstream asks for an email code, both the address and the code are forwarded
     * untouched: the code lives in New API's process memory, so this site can only carry it, never
     * create or check it. A successful call is therefore the one and only proof that the address
     * belongs to the person registering.</p>
     *
     * @throws com.hub.backend.infra.newapi.NewApiRegistrationException when New API refuses
     */
    public NewApiLoginSession registerAccount(String username,
                                              String password,
                                              String email,
                                              String verificationCode) {
        requireEnabled();
        String resolved = requireUsername(username);
        newApi.registerUser(resolved, password, email, verificationCode);
        return newApi.login(resolved, password);
    }

    /**
     * Asks New API to mail a sign-up code to {@code email}.
     *
     * <p>Deliberately no local bookkeeping: the address is not remembered, the code is not stored,
     * and nothing is written to this database. The only state is New API's in-memory map.</p>
     */
    public void sendVerificationCode(String email) {
        requireEnabled();
        newApi.sendEmailVerification(email);
    }

    /**
     * Whether the upstream deployment requires an email code to register.
     *
     * <p>Answers {@code false} when the integration is off: with no New API behind registration
     * there is nothing to verify against, and no code could ever be produced.</p>
     */
    public boolean isEmailVerificationEnabled() {
        if (!properties.isEnabled()) {
            return false;
        }
        return newApi.isEmailVerificationEnabled();
    }

    /** The New API user id already bound to this account, when it is known. */
    public Optional<Long> boundNewApiUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return accounts.findByUserId(userId)
                .map(NewApiAccountEntity::getNewapiUserId)
                .filter(id -> id != null);
    }

    /**
     * Clears the remote id after that New API account has been deleted.
     *
     * <p>Silently does nothing when there is no binding; a missing row simply means this account
     * never reached New API.</p>
     */
    public void forgetRemoteIdentity(Long userId) {
        if (userId == null) {
            return;
        }
        accounts.findByUserId(userId).ifPresent(row -> {
            row.unbindNewApiUser();
            save(row);
        });
    }

    /**
     * The password of the current session, used to sign in to New API as this user.
     *
     * <p>It comes from memory, never from the database: the only password this site stores is a
     * hash. A missing entry means the session predates a restart (or the entry expired) — the
     * access token is still valid, so this answers 428 "confirm the password" instead of 401 or
     * 503. The client shows one password prompt and retries the same request.</p>
     */
    public String loginPassword(CurrentUser user) {
        validateUser(user);
        return passwords.password(user.userId()).orElseThrow(() ->
                new PasswordConfirmationRequiredException(
                        "The New API password of this account is not held by this server session; "
                                + "confirm the account password to continue"));
    }

    /**
     * Records a remote identity only after the user's own credentials proved ownership.
     *
     * <p>A New API user can belong to at most one local account. A repeated bind is idempotent;
     * a conflicting id fails instead of silently adopting somebody else's identity.</p>
     */
    public void bindIdentity(Long userId,
                             Long siteId,
                             String username,
                             Long newapiUserId,
                             boolean trusted) {
        if (userId == null || siteId == null || newapiUserId == null) {
            throw new UnauthorizedException(ApiMessages.LOGIN_REQUIRED);
        }
        String resolved = requireUsername(username);
        String conflict = "New API user '%s' is already bound to another account"
                .formatted(resolved);

        Optional<NewApiAccountEntity> owner = accounts.findByNewapiUserId(newapiUserId);
        if (owner.isPresent() && !owner.get().getUserId().equals(userId)) {
            throw new ConflictException(conflict);
        }

        Optional<NewApiAccountEntity> existing = accounts.findByUserId(userId);
        if (existing.isPresent()) {
            NewApiAccountEntity row = existing.get();
            if (row.getNewapiUserId() != null && !row.getNewapiUserId().equals(newapiUserId)) {
                throw new ConflictException(conflict);
            }
            boolean dirty = false;
            if (row.getNewapiUserId() == null) {
                row.bindNewApiUser(newapiUserId);
                dirty = true;
            }
            if (trusted && !row.isTrusted()) {
                row.markTrusted();
                dirty = true;
            }
            if (dirty) {
                save(row);
            }
            return;
        }

        NewApiAccountEntity row = new NewApiAccountEntity(siteId, userId, resolved);
        row.bindNewApiUser(newapiUserId);
        if (trusted) {
            row.markTrusted();
        }
        save(row);
    }

    private void save(NewApiAccountEntity row) {
        try {
            accounts.save(row);
        } catch (DataIntegrityViolationException race) {
            NewApiAccountEntity reloaded = accounts.findByUserId(row.getUserId())
                    .orElseThrow(() -> race);
            if (reloaded.getNewapiUserId() != null
                    && row.getNewapiUserId() != null
                    && !reloaded.getNewapiUserId().equals(row.getNewapiUserId())) {
                throw new ConflictException(
                        "New API user is already bound to another account");
            }
            if (reloaded.getNewapiUserId() == null && row.getNewapiUserId() != null) {
                reloaded.bindNewApiUser(row.getNewapiUserId());
            }
            if (row.isTrusted()) {
                reloaded.markTrusted();
            }
            accounts.save(reloaded);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("New API integration is disabled");
        }
    }

    private static void validateUser(CurrentUser user) {
        if (user == null || user.userId() == null || user.siteId() == null) {
            throw new UnauthorizedException(ApiMessages.LOGIN_REQUIRED);
        }
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ServiceUnavailableException(
                    "This account has no username, so it has no New API identity");
        }
        return username.strip();
    }
}
