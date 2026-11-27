package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads and writes the outline JSON column.
 *
 * <p>Parsing failures are surfaced as {@code 400} with a stable message instead of a stack trace:
 * a corrupt outline means the client is about to lose work, and it deserves an explicit answer
 * rather than a generic 500.</p>
 */
@Component
public class ThesisOutlineCodec {

    private static final TypeReference<List<ThesisOutlineNode>> TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ThesisOutlineCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ThesisOutlineNode> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ThesisOutlineNode> nodes = objectMapper.readValue(json, TYPE);
            return nodes == null ? List.of() : nodes;
        } catch (JsonProcessingException ex) {
            throw new InvalidRequestException("outline document is corrupt and cannot be read");
        }
    }

    public String write(List<ThesisOutlineNode> nodes) {
        try {
            return objectMapper.writeValueAsString(nodes);
        } catch (JsonProcessingException ex) {
            throw new InvalidRequestException("outline document cannot be serialised");
        }
    }
}
