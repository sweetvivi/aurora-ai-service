package com.hub.backend.modules.model;

import com.hub.backend.modules.model.dto.ModelCapabilities;

import java.util.Locale;

/**
 * Family defaults for chat models that declare {@code is_thinking} but have not yet been given an
 * explicit upstream toggle in {@code capabilities_json}.
 *
 * <p>DeepSeek (and most OpenAI-compatible relays such as Aurora) honour top-level
 * {@code reasoning_effort}: {@code none} disables chain-of-thought upstream; {@code high} is
 * DeepSeek's documented default effort. GPT-style keys use {@code medium}/{@code none}. Explicit
 * catalogue values always win.</p>
 */
public final class ChatThinkingDefaults {

    public record Spec(String parameter, String onValue, String offValue) {
    }

    private ChatThinkingDefaults() {
    }

    /**
     * @return the effective thinking request field, or {@code null} when the model does not support
     *         thinking or no family default applies
     */
    public static Spec resolve(String modelKey, boolean thinking, ModelCapabilities capabilities) {
        if (!thinking) {
            return null;
        }
        String configured = capabilities == null ? null : blankToNull(capabilities.thinkingParameter());
        if (configured != null) {
            return new Spec(
                    configured,
                    capabilities == null ? null : blankToNull(capabilities.thinkingOnValue()),
                    capabilities == null ? null : blankToNull(capabilities.thinkingOffValue()));
        }
        return familyDefault(modelKey);
    }

    /** Whether the catalogue should expose the deep-think switch for this row. */
    public static boolean exposesSwitch(String modelKey, boolean thinking, ModelCapabilities capabilities) {
        return resolve(modelKey, thinking, capabilities) != null;
    }

    static Spec familyDefault(String modelKey) {
        String key = modelKey == null ? "" : modelKey.strip().toLowerCase(Locale.ROOT);
        if (key.startsWith("deepseek")) {
            return new Spec("reasoning_effort", "high", "none");
        }
        if (key.startsWith("gpt-")
                || key.startsWith("gt-")
                || key.startsWith("o1")
                || key.startsWith("o3")
                || key.startsWith("o4")) {
            return new Spec("reasoning_effort", "medium", "none");
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        return text.isEmpty() ? null : text;
    }
}
