package com.hub.backend.infra.newapi;

import java.util.Locale;

/**
 * A refusal of the public New API registration endpoint.
 *
 * <p>New API answers a refused registration with HTTP 200 and {@code success: false}, so the only
 * thing that tells "the name is taken" apart from "registration is closed upstream" is the message
 * text. That text is classified once, here at the boundary, instead of being re-parsed by callers:
 * a caller that guesses wrong either answers 409 for a temporary outage or lets a registration
 * through with no New API account behind it.</p>
 */
public class NewApiRegistrationException extends RuntimeException {

    /** Why registration was refused, as far as the upstream message can tell. */
    public enum Kind {
        /** The username already belongs to someone. */
        ALREADY_EXISTS,
        /**
         * The email address already belongs to another New API account.
         *
         * <p>Separate from {@link #ALREADY_EXISTS} on purpose: a taken username may be claimed by
         * proving the password, but a taken address must never be claimed that way.</p>
         */
        EMAIL_TAKEN,
        /** The code forwarded from the user is wrong or past its ten minutes. */
        VERIFICATION_CODE_INVALID,
        /** The deployment wants an email address and code that this request did not carry. */
        EMAIL_VERIFICATION_REQUIRED,
        /** Public registration or password registration is switched off upstream. */
        DISABLED,
        /** Anything else, including transport failures and unexpected bodies. */
        FAILED
    }

    private final Kind kind;

    public NewApiRegistrationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Classifies an upstream refusal by its message text. */
    public static NewApiRegistrationException fromMessage(String message) {
        return new NewApiRegistrationException(classify(message), message);
    }

    /**
     * Maps the upstream sentence onto a kind.
     *
     * <p>Order matters. "Email address is already in use" contains both "email" and "already in
     * use", and "Verification code is incorrect or has expired" contains "verification code" — the
     * narrower reading has to win, otherwise a taken address turns into a claim attempt and a
     * mistyped code turns into "registration is closed".</p>
     */
    static Kind classify(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);

        if (mentionsEmail(text) && mentionsTaken(text)) {
            return Kind.EMAIL_TAKEN;
        }
        if (text.contains("username") && mentionsTaken(text)) {
            return Kind.ALREADY_EXISTS;
        }
        if (text.contains("already exist") || text.contains("already in use")) {
            return Kind.ALREADY_EXISTS;
        }
        if (mentionsCode(text) && mentionsRejection(text)) {
            return Kind.VERIFICATION_CODE_INVALID;
        }
        if (text.contains("email verification")
                || text.contains("verification code")
                || text.contains("email address")) {
            return Kind.EMAIL_VERIFICATION_REQUIRED;
        }
        if (text.contains("registration has been disabled")
                || text.contains("password registration has been disabled")) {
            return Kind.DISABLED;
        }
        return Kind.FAILED;
    }

    private static boolean mentionsEmail(String text) {
        return text.contains("email") || text.contains("邮箱");
    }

    /**
     * Whether the sentence is about the verification code itself.
     *
     * <p>Required before reading "invalid" as a bad code: New API answers a malformed body with
     * "Invalid input Key: 'Username'", and treating that as a mistyped code would tell the user to
     * fetch a new one instead of fixing the request.</p>
     */
    private static boolean mentionsCode(String text) {
        return text.contains("verification") || text.contains("验证码");
    }

    private static boolean mentionsRejection(String text) {
        return text.contains("incorrect")
                || text.contains("expired")
                || text.contains("invalid")
                || text.contains("错误")
                || text.contains("过期");
    }

    private static boolean mentionsTaken(String text) {
        return text.contains("already")
                || text.contains("in use")
                || text.contains("已被")
                || text.contains("已存在")
                || text.contains("占用");
    }
}
