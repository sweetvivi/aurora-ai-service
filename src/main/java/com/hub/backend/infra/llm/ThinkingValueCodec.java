package com.hub.backend.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Coerces admin-configured thinking on/off strings into JSON-friendly request values.
 *
 * <p>Defaults keep DeepSeek-style {@code enable_thinking: true/false} working when operators only
 * configure the parameter name. Explicit strings such as {@code medium} / {@code none} pass through
 * for GPT-style {@code reasoning_effort}. JSON object/array literals such as
 * {@code {"type":"enabled"}} are parsed so DeepSeek's official {@code thinking} object form can be
 * configured from the admin UI.</p>
 */
public final class ThinkingValueCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ThinkingValueCodec() {
    }

    /**
     * @param raw              configured value, blank means use the boolean default
     * @param fallbackBoolean  {@code true} when thinking is on, {@code false} when off
     */
    public static Object coerce(String raw, boolean fallbackBoolean) {
        if (raw == null || raw.isBlank()) {
            return fallbackBoolean;
        }
        String text = raw.strip();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        if (text.matches("-?\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        if (text.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        Object json = tryParseJson(text);
        if (json != null) {
            return json;
        }
        return text;
    }

    private static Object tryParseJson(String text) {
        if (!(text.startsWith("{") || text.startsWith("["))) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node != null && (node.isObject() || node.isArray())) {
                return JSON.convertValue(node, Object.class);
            }
        } catch (JsonProcessingException ignored) {
            // fall through — treat as a plain string
        }
        return null;
    }
}
