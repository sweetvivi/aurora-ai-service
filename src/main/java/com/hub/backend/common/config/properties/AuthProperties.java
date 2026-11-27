package com.hub.backend.common.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Authentication tokens.
 *
 * <p>Bound from {@code app.auth.*}. The access token is a signed, short lived JWT; the refresh
 * token is an opaque random secret whose SHA-256 is the only thing persisted, per
 * {@code docs/ai1foo-rebuild-plan.md} §8.1.</p>
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** Minimum HMAC key length accepted for HS256. */
    public static final int MIN_SECRET_LENGTH = 32;

    /** Signing key of the access token. Supplied by {@code AUTH_JWT_SECRET}; never defaulted. */
    private String jwtSecret = "";

    /** {@code iss} claim written into every access token. */
    private String issuer = "backend-service";

    /** Lifetime of an access token. Kept short because it cannot be revoked directly. */
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    /** Lifetime of a refresh token, which equals the lifetime of the session row. */
    private Duration refreshTokenTtl = Duration.ofDays(30);

    /** Legacy BCrypt work factor, retained only to verify hashes created before Argon2id. */
    private int bcryptStrength = 12;

    /** Argon2id salt length in bytes. */
    private int argon2SaltLength = 16;

    /** Argon2id hash length in bytes. */
    private int argon2HashLength = 32;

    /** Argon2id parallelism. */
    private int argon2Parallelism = 1;

    /** Argon2id memory cost in KiB. */
    private int argon2MemoryKb = 19456;

    /** Argon2id iteration count. */
    private int argon2Iterations = 2;

    /**
     * Whether a name New API knows may open an account here on its first sign in.
     *
     * <p>On by default because the name is the shared identity: an account registered on New API is
     * meant to be usable here. The consequence is that whatever gates registration on New API also
     * gates registration here — if that deployment allows public sign up, anyone who signs up there
     * gets an account here. Turn this off to require a local registration first.</p>
     */
    private boolean newApiAccountProvisioningEnabled = true;

    /**
     * Whether a registration here also creates the New API account, through New API's public
     * sign-up endpoint (no administrator token involved).
     *
     * <p>On by default because the local account is useless without the remote one: every AI call
     * signs in to New API with the user's own credentials. Registration therefore fails rather than
     * half-succeeding — a local-only account would look fine until the first generation. Turn this
     * off only when New API accounts are provisioned separately and users are expected to sign in,
     * not sign up.</p>
     */
    private boolean newApiRegistrationEnabled = true;

    /**
     * How long the in-memory copy of a sign-in password may be used to open the user's New API
     * account.
     *
     * <p>Matches the refresh token lifetime by default: while the session can be renewed, the relay
     * key can be (re)provisioned without asking for the password again. It is never persisted, so a
     * restart simply costs one extra sign in.</p>
     */
    private Duration sessionPasswordTtl = Duration.ofDays(30);

    /** How the site treats the email address that New API verifies at sign up. */
    private EmailVerification emailVerification = new EmailVerification();

    /**
     * Email verification is owned by New API, not by this site.
     *
     * <p>This site has no mail transport and keeps no verification codes: it forwards the upstream
     * code request and passes the code back on registration, which is the only place New API
     * verifies an address. What is configurable here is therefore only <em>when the address is
     * required</em> and <em>whether sign in waits for it</em>.</p>
     */
    public static class EmailVerification {

        /** Whether a registration has to carry an upstream-verified email address. */
        public enum RegisterMode {
            /** Never ask, even when the upstream deployment requires it. */
            OFF,
            /** Follow {@code GET /api/status} → {@code email_verification}. */
            AUTO,
            /** Always ask; registration fails when the upstream cannot verify it. */
            ON
        }

        private RegisterMode registerMode = RegisterMode.AUTO;

        /**
         * Whether signing in requires the account's email to have been verified.
         *
         * <p>Off by default because every account created before this switch existed has no
         * verified address, and turning it on without backfilling would lock them all out. See
         * {@code docs/newapi-deployment-checklist.md} §5.</p>
         */
        private boolean loginRequiresVerifiedEmail = false;

        public boolean requiresRegistrationEmail(boolean upstreamVerificationEnabled) {
            return switch (registerMode) {
                case ON -> true;
                case OFF -> false;
                case AUTO -> upstreamVerificationEnabled;
            };
        }

        public RegisterMode getRegisterMode() {
            return registerMode;
        }

        public void setRegisterMode(RegisterMode registerMode) {
            this.registerMode = registerMode == null ? RegisterMode.AUTO : registerMode;
        }

        public boolean isLoginRequiresVerifiedEmail() {
            return loginRequiresVerifiedEmail;
        }

        public void setLoginRequiresVerifiedEmail(boolean loginRequiresVerifiedEmail) {
            this.loginRequiresVerifiedEmail = loginRequiresVerifiedEmail;
        }
    }

    public EmailVerification getEmailVerification() {
        return emailVerification;
    }

    public void setEmailVerification(EmailVerification emailVerification) {
        this.emailVerification = emailVerification;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public int getBcryptStrength() {
        return bcryptStrength;
    }

    public void setBcryptStrength(int bcryptStrength) {
        this.bcryptStrength = bcryptStrength;
    }

    public int getArgon2SaltLength() {
        return argon2SaltLength;
    }

    public void setArgon2SaltLength(int argon2SaltLength) {
        this.argon2SaltLength = argon2SaltLength;
    }

    public int getArgon2HashLength() {
        return argon2HashLength;
    }

    public void setArgon2HashLength(int argon2HashLength) {
        this.argon2HashLength = argon2HashLength;
    }

    public int getArgon2Parallelism() {
        return argon2Parallelism;
    }

    public void setArgon2Parallelism(int argon2Parallelism) {
        this.argon2Parallelism = argon2Parallelism;
    }

    public int getArgon2MemoryKb() {
        return argon2MemoryKb;
    }

    public void setArgon2MemoryKb(int argon2MemoryKb) {
        this.argon2MemoryKb = argon2MemoryKb;
    }

    public int getArgon2Iterations() {
        return argon2Iterations;
    }

    public void setArgon2Iterations(int argon2Iterations) {
        this.argon2Iterations = argon2Iterations;
    }

    public boolean isNewApiAccountProvisioningEnabled() {
        return newApiAccountProvisioningEnabled;
    }

    public void setNewApiAccountProvisioningEnabled(boolean newApiAccountProvisioningEnabled) {
        this.newApiAccountProvisioningEnabled = newApiAccountProvisioningEnabled;
    }

    public boolean isNewApiRegistrationEnabled() {
        return newApiRegistrationEnabled;
    }

    public void setNewApiRegistrationEnabled(boolean newApiRegistrationEnabled) {
        this.newApiRegistrationEnabled = newApiRegistrationEnabled;
    }

    public void setSessionPasswordTtl(Duration sessionPasswordTtl) {
        this.sessionPasswordTtl = sessionPasswordTtl;
    }

    public Duration getSessionPasswordTtl() {
        return sessionPasswordTtl;
    }
}
