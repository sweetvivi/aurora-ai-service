package com.hub.backend.common.security;

import com.hub.backend.common.config.properties.AuthProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plaintext sign-in password of an active session, held in memory only.
 *
 * <p>Two steps of opening a user's AI access need that user's own password: signing in to New API
 * as them, and the password proof that creating their PAT requires. The database deliberately keeps
 * only a hash (V76 removed the encrypted copy), so the password is kept here instead — for the
 * lifetime of the session, never on disk, dropped on sign out, password change and account
 * deletion.</p>
 *
 * <p>A restart empties this vault. Nothing breaks: the next AI call cannot re-open the account and
 * answers "sign in again", after which provisioning resumes. That is the price of not storing a
 * reversible copy of every user's password.</p>
 */
@Component
public class SessionPasswordVault {

    /** Hard ceiling so a long-running instance cannot grow without bound. */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public SessionPasswordVault(Clock clock, AuthProperties authProperties) {
        this.clock = clock;
        this.ttl = authProperties.getSessionPasswordTtl();
    }

    /** Records the password behind a successful sign in or registration. */
    public void remember(Long userId, String password) {
        if (userId == null || password == null || password.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        entries.put(userId, new Entry(password, now.plus(ttl)));
        if (entries.size() > MAX_ENTRIES) {
            evict(now);
        }
    }

    /** The password of this session, when it is still held and not expired. */
    public Optional<String> password(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(userId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(userId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.password());
    }

    /** Drops the password of one account: sign out, password change, account deletion. */
    public void forget(Long userId) {
        if (userId != null) {
            entries.remove(userId);
        }
    }

    /**
     * Keeps the vault under its ceiling: expired entries first, then the entries closest to
     * expiry. Only the memory copy is dropped, so the worst case is one extra sign in.
     */
    private void evict(Instant now) {
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        int excess = entries.size() - MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        entries.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    private record Entry(String password, Instant expiresAt) {
    }
}
