package com.hub.backend.infra.llm.dto;

import java.util.List;

/**
 * One increment of a streamed chat completion.
 *
 * @param id             upstream completion id, echoed on every frame
 * @param contentDelta   visible answer increment
 * @param reasoningDelta reasoning/thinking increment when the provider exposes it
 * @param finishReason   stop reason, only present on the final content frame
 * @param done           {@code true} for the terminal {@code [DONE]} sentinel
 * @param usage          token accounting reported on a stream frame, when available
 * @param toolCallDeltas tool call fragments carried by this frame, empty for ordinary content
 */
public record LlmStreamChunk(
        String id,
        String contentDelta,
        String reasoningDelta,
        String finishReason,
        boolean done,
        LlmUsage usage,
        List<LlmToolCallDelta> toolCallDeltas
) {

    public LlmStreamChunk {
        toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
    }

    public LlmStreamChunk(String id,
                          String contentDelta,
                          String reasoningDelta,
                          String finishReason,
                          boolean done,
                          LlmUsage usage) {
        this(id, contentDelta, reasoningDelta, finishReason, done, usage, List.of());
    }

    public LlmStreamChunk(String id,
                          String contentDelta,
                          String reasoningDelta,
                          String finishReason,
                          boolean done) {
        this(id, contentDelta, reasoningDelta, finishReason, done, null, List.of());
    }

    public static LlmStreamChunk done(String id) {
        return new LlmStreamChunk(id, null, null, null, true, null, List.of());
    }

    public boolean hasContent() {
        return contentDelta != null && !contentDelta.isEmpty();
    }

    public boolean hasReasoning() {
        return reasoningDelta != null && !reasoningDelta.isEmpty();
    }

    public boolean hasToolCalls() {
        return !toolCallDeltas.isEmpty();
    }
}
