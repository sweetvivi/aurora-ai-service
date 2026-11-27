package com.hub.backend.infra.llm.dto;

import java.util.Map;

/**
 * A tool the model is allowed to call during this request.
 *
 * <p>Provider neutral: adapters translate {@code parameters} into whatever shape their wire format
 * expects, so business code never writes OpenAI-specific JSON.</p>
 *
 * @param name        function name, unique inside one request
 * @param description what the tool does; the model decides from this text
 * @param parameters  JSON Schema object describing the accepted arguments
 */
public record LlmToolDefinition(String name, String description, Map<String, Object> parameters) {

    public LlmToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        parameters = parameters == null
                ? Map.of("type", "object", "properties", Map.of())
                : Map.copyOf(parameters);
    }
}
