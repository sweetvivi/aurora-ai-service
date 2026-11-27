package com.hub.backend.infra.llm.dto;

/**
 * A finished tool invocation requested by the model.
 *
 * <p>Emitted by the streaming path once all argument fragments of one call have been accumulated,
 * then echoed back to the model as an assistant message followed by a
 * {@link LlmChatMessage#toolResult} carrying the output.</p>
 *
 * @param id        upstream call id, referenced by the tool result message
 * @param name      function name the model picked
 * @param arguments raw JSON string produced by the model; may be malformed
 */
public record LlmToolCall(String id, String name, String arguments) {
}
