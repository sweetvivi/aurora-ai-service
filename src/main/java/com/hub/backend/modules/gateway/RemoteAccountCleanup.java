package com.hub.backend.modules.gateway;

/**
 * What happened to the New API account behind a local account that is being deleted.
 *
 * <p>Deletion paths are not allowed to fail because New API is unavailable: the local row goes
 * either way. The caller therefore needs to know whether the remote side was actually dealt with —
 * an operator deleting a user from the admin console should be told when the upstream row survived,
 * instead of finding an orphan account later.</p>
 */
public enum RemoteAccountCleanup {

    /** The upstream row is gone. */
    DELETED,

    /** The upstream was temporarily unavailable; the deletion is queued and will be retried. */
    QUEUED,

    /**
     * The upstream row survives. Either no credential could open it (an administrator is not
     * configured and this server no longer holds the user's password) or New API refused.
     */
    LEFT_IN_PLACE,

    /** Nothing to do: the integration is off, or this account never had a New API identity. */
    NOT_APPLICABLE;

    /**
     * True when the upstream row is gone, or its removal is already queued — in both cases this
     * deletion has nothing left to do about the account itself.
     *
     * <p>Deliberately excludes {@link #NOT_APPLICABLE}: "no account to delete" does not mean the
     * keys hanging off it are gone, and the caller still has to clear those.</p>
     */
    public boolean isResolved() {
        return this == DELETED || this == QUEUED;
    }
}
