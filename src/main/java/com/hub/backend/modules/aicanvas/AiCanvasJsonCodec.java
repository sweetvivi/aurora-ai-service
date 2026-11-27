package com.hub.backend.modules.aicanvas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JSON helpers shared by the AI Canvas endpoints.
 */
@Component
public class AiCanvasJsonCodec {

    private final ObjectMapper objectMapper;

    public AiCanvasJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new InvalidRequestException("AI Canvas JSON document is invalid");
        }
    }

    public JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored AI Canvas JSON is invalid", exception);
        }
    }

    public JsonNode parseModelJson(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Model returned an empty JSON response");
        }
        String candidate = value.strip();
        if (candidate.startsWith("```")) {
            int firstLine = candidate.indexOf('\n');
            int lastFence = candidate.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                candidate = candidate.substring(firstLine + 1, lastFence).strip();
            }
        }

        int objectStart = candidate.indexOf('{');
        int arrayStart = candidate.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start < 0) {
            throw new InvalidRequestException("Model response does not contain JSON");
        }

        char opening = candidate.charAt(start);
        char closing = opening == '{' ? '}' : ']';
        int end = matchingEnd(candidate, start, opening, closing);
        if (end < 0) {
            throw new InvalidRequestException("Model returned incomplete JSON");
        }
        try {
            return objectMapper.readTree(candidate.substring(start, end + 1));
        } catch (JsonProcessingException exception) {
            throw new InvalidRequestException("Model returned malformed JSON");
        }
    }

    public static void requireArray(JsonNode value, String field) {
        if (value == null || !value.isArray()) {
            throw new InvalidRequestException("%s must be a JSON array".formatted(field));
        }
    }

    public static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new InvalidRequestException("%s must be a JSON object".formatted(field));
        }
    }

    public static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static int matchingEnd(String value, int start, char opening, char closing) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == opening) {
                depth++;
            } else if (current == closing && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
