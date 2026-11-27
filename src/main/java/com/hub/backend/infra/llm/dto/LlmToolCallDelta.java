package com.hub.backend.infra.llm.dto;

/**
 * One fragment of a streamed tool call.
 *
 * <p>OpenAI-compatible gateways stream {@code tool_calls} the same way they stream content: the
 * first fragment carries {@code id} and {@code name}, every fragment carries a slice of the JSON
 * arguments. Fragments are grouped by {@code index}; a single frame may hold several, one per
 * parallel call.</p>
 *
 * @param index          position of the call inside the message
 * @param id             upstream call id, usually only on the first fragment
 * @param name           function name, usually only on the first fragment
 * @param argumentsDelta argument fragment, may be {@code null}
 */
public record LlmToolCallDelta(int index, String id, String name, String argumentsDelta) {
}
