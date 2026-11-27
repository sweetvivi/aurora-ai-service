package com.hub.backend.modules.model;

import com.hub.backend.modules.model.dto.ModelCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatThinkingDefaultsTest {

    @Test
    void deepSeekFamilyDefaultsToReasoningEffort() {
        ChatThinkingDefaults.Spec spec = ChatThinkingDefaults.resolve(
                "deepseek-v4.1-flash", true, null);

        assertEquals("reasoning_effort", spec.parameter());
        assertEquals("high", spec.onValue());
        assertEquals("none", spec.offValue());
        assertTrue(ChatThinkingDefaults.exposesSwitch("deepseek-v4.1-flash", true, null));
    }

    @Test
    void gptFamilyDefaultsToMediumEffort() {
        ChatThinkingDefaults.Spec spec = ChatThinkingDefaults.resolve("gt-6.1-sol", true, null);

        assertEquals("reasoning_effort", spec.parameter());
        assertEquals("medium", spec.onValue());
        assertEquals("none", spec.offValue());
    }

    @Test
    void explicitCatalogueValuesWin() {
        ModelCapabilities capabilities = new ModelCapabilities(
                null, null, null, null, null, 0, false,
                "thinking", "{\"type\":\"enabled\"}", "{\"type\":\"disabled\"}");

        ChatThinkingDefaults.Spec spec = ChatThinkingDefaults.resolve(
                "deepseek-v4-pro", true, capabilities);

        assertEquals("thinking", spec.parameter());
        assertEquals("{\"type\":\"enabled\"}", spec.onValue());
        assertEquals("{\"type\":\"disabled\"}", spec.offValue());
    }

    @Test
    void noDefaultWhenThinkingFlagIsOff() {
        assertNull(ChatThinkingDefaults.resolve("deepseek-v4.1-flash", false, null));
        assertFalse(ChatThinkingDefaults.exposesSwitch("deepseek-v4.1-flash", false, null));
    }
}
