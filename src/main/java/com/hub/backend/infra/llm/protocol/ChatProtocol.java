package com.hub.backend.infra.llm.protocol;

import java.util.Locale;

/**
 * Wire format a supplier speaks.
 *
 * <p>{@code llm_providers.protocol} has always been stored, but until now nothing read it: every
 * supplier was called as if it were an OpenAI-compatible gateway. A supplier configured as
 * {@code ANTHROPIC} or {@code GEMINI} therefore sent a request the other end could not parse. This
 * type is what makes the setting mean something.</p>
 */
public enum ChatProtocol {

    /** OpenAI's own API. */
    OPENAI,

    /** Any gateway that mimics OpenAI's chat completions. */
    OPENAI_COMPATIBLE,

    /** Azure OpenAI deployments: different path shape and an {@code api-key} header. */
    AZURE_OPENAI,

    /** Ollama's OpenAI-compatible endpoint, usually without authentication. */
    OLLAMA,

    /** Anthropic Messages API. */
    ANTHROPIC,

    /** Google Gemini generateContent API. */
    GEMINI;

    /**
     * @param raw value from the provider row, may be {@code null}
     * @return the matching protocol; anything unrecognised or blank falls back to
     *         {@link #OPENAI_COMPATIBLE}, which is the most permissive option and matches the
     *         behaviour every supplier had before protocols were honoured
     */
    public static ChatProtocol resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return OPENAI_COMPATIBLE;
        }
    }

    /** @return human readable name for error messages */
    public String label() {
        return switch (this) {
            case OPENAI -> "OpenAI";
            case OPENAI_COMPATIBLE -> "OpenAI-compatible";
            case AZURE_OPENAI -> "Azure OpenAI";
            case OLLAMA -> "Ollama";
            case ANTHROPIC -> "Anthropic Messages";
            case GEMINI -> "Google Gemini";
        };
    }
}
