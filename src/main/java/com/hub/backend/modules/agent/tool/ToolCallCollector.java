package com.hub.backend.modules.agent.tool;

import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolCallDelta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds whole tool calls from the fragments a streaming response delivers.
 *
 * <p>The OpenAI protocol sends one call split across many chunks: the id and name arrive once,
 * while the JSON arguments trickle in. A model may also start several calls in the same turn, so
 * everything is keyed by {@code index} and only assembled once the stream ends.</p>
 */
public class ToolCallCollector {

    private final Map<Integer, String> ids = new LinkedHashMap<>();
    private final Map<Integer, String> names = new LinkedHashMap<>();
    private final Map<Integer, StringBuilder> arguments = new LinkedHashMap<>();

    public void accept(List<LlmToolCallDelta> deltas) {
        for (LlmToolCallDelta delta : deltas) {
            if (delta.id() != null && !delta.id().isBlank()) {
                ids.put(delta.index(), delta.id());
            }
            if (delta.name() != null && !delta.name().isBlank()) {
                names.put(delta.index(), delta.name());
            }
            if (delta.argumentsDelta() != null) {
                arguments.computeIfAbsent(delta.index(), key -> new StringBuilder())
                        .append(delta.argumentsDelta());
            }
        }
    }

    /** Whether the model asked for at least one tool during this round. */
    public boolean hasCalls() {
        return !names.isEmpty();
    }

    /** Complete calls, in the order the model began them. */
    public List<LlmToolCall> calls() {
        List<LlmToolCall> calls = new ArrayList<>(names.size());
        names.forEach((index, name) -> {
            StringBuilder buffer = arguments.get(index);
            calls.add(new LlmToolCall(
                    ids.getOrDefault(index, "call_" + index),
                    name,
                    buffer == null || buffer.isEmpty() ? "{}" : buffer.toString()));
        });
        return calls;
    }
}
