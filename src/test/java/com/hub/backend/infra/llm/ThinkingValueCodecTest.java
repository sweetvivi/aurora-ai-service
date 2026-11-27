package com.hub.backend.infra.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThinkingValueCodecTest {

    @Test
    void defaultsBlankToBooleanFallback() {
        assertEquals(Boolean.TRUE, ThinkingValueCodec.coerce(null, true));
        assertEquals(Boolean.FALSE, ThinkingValueCodec.coerce("  ", false));
    }

    @Test
    void coercesBooleanAndNumericLiterals() {
        assertEquals(Boolean.TRUE, ThinkingValueCodec.coerce("true", false));
        assertEquals(Boolean.FALSE, ThinkingValueCodec.coerce("FALSE", true));
        assertEquals(3L, ThinkingValueCodec.coerce("3", false));
        assertEquals(1.5d, ThinkingValueCodec.coerce("1.5", false));
    }

    @Test
    void keepsProviderSpecificStrings() {
        assertEquals("medium", ThinkingValueCodec.coerce("medium", true));
        assertEquals("none", ThinkingValueCodec.coerce("none", false));
    }

    @Test
    void parsesDeepSeekThinkingObjectLiterals() {
        assertEquals(Map.of("type", "enabled"),
                ThinkingValueCodec.coerce("{\"type\":\"enabled\"}", false));
        assertEquals(Map.of("type", "disabled"),
                ThinkingValueCodec.coerce(" {\"type\":\"disabled\"} ", true));
    }

    @Test
    void keepsMalformedJsonLookingStrings() {
        assertEquals("{not-json", ThinkingValueCodec.coerce("{not-json", true));
    }
}
