package com.hub.backend.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmToolCallDelta;
import com.hub.backend.infra.llm.dto.LlmUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stateless decoder for the {@code text/event-stream} frames of an OpenAI-compatible gateway.
 *
 * <p>Only the pieces the platform needs are extracted: the visible content delta, the reasoning
 * delta, the stop reason and tool call fragments. Frames that carry nothing useful (role
 * handshake, keep-alive comments, blank separators) are reported as {@link Optional#empty()} so
 * callers can simply ignore them.</p>
 */
public class LlmSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_SENTINEL = "[DONE]";

    private final ObjectMapper objectMapper;

    public LlmSseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Decodes a single raw line of the event stream.
     *
     * @param line raw line as read from the wire, may be {@code null}
     * @return the decoded increment, or empty when the line carries no payload
     * @throws LlmInvocationException when a {@code data:} frame is not valid JSON
     */
    public Optional<LlmStreamChunk> parseLine(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return Optional.empty();
        }
        if (!trimmed.regionMatches(true, 0, DATA_PREFIX, 0, DATA_PREFIX.length())) {
            return Optional.empty();
        }

        String payload = trimmed.substring(DATA_PREFIX.length()).strip();
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        if (DONE_SENTINEL.equals(payload)) {
            return Optional.of(LlmStreamChunk.done(null));
        }
        return Optional.of(toChunk(payload));
    }

    /**
     * Convenience overload used when only the JSON payload of a frame is available.
     */
    public LlmStreamChunk parsePayload(String json) {
        if (json == null || json.isBlank() || DONE_SENTINEL.equals(json.strip())) {
            return LlmStreamChunk.done(null);
        }
        return toChunk(json);
    }

    private LlmStreamChunk toChunk(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException("Unable to parse upstream stream frame", exception);
        }

        String id = textOrNull(root.path("id"));
        LlmUsage usage = usageOrNull(root.path("usage"));
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode() || choice.isNull()) {
            return new LlmStreamChunk(id, null, null, null, false, usage, List.of());
        }

        JsonNode delta = choice.path("delta");
        return new LlmStreamChunk(
                id,
                textOrNull(delta.path("content")),
                reasoningDelta(delta),
                textOrNull(choice.path("finish_reason")),
                false,
                usage,
                toolCallDeltas(delta.path("tool_calls")));
    }

    /**
     * OpenAI-compatible gateways disagree on the field name for chain-of-thought deltas.
     * DeepSeek / 百炼 use {@code reasoning_content}; many GPT / vLLM / OpenRouter style
     * endpoints emit {@code reasoning} (or occasionally {@code reasoning_text}).
     */
    private static String reasoningDelta(JsonNode delta) {
        String reasoning = textOrNull(delta.path("reasoning_content"));
        if (reasoning != null) {
            return reasoning;
        }
        reasoning = textOrNull(delta.path("reasoning"));
        if (reasoning != null) {
            return reasoning;
        }
        return textOrNull(delta.path("reasoning_text"));
    }

    /**
     * Tool calls arrive as fragments: the first one for an {@code index} carries id and name, the
     * rest only carry pieces of the JSON arguments. Callers accumulate by index.
     */
    private static List<LlmToolCallDelta> toolCallDeltas(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<LlmToolCallDelta> deltas = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            JsonNode function = item.path("function");
            deltas.add(new LlmToolCallDelta(
                    item.path("index").asInt(0),
                    textOrNull(item.path("id")),
                    textOrNull(function.path("name")),
                    textOrNull(function.path("arguments"))));
        }
        return deltas;
    }

    private static LlmUsage usageOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new LlmUsage(
                node.path("prompt_tokens").asInt(0),
                node.path("completion_tokens").asInt(0),
                node.path("total_tokens").asInt(0));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isEmpty() ? null : value;
    }
}
