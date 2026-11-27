package com.hub.backend.infra.llm.dto;

/**
 * Result of a non streaming chat completion.
 *
 * @param id           upstream completion id
 * @param model        model reported by the provider
 * @param content      assistant text, may be {@code null} when the provider returned only reasoning
 * @param finishReason stop reason reported by the provider
 * @param usage        token accounting, never {@code null}
 */
public record LlmChatCompletion(
        String id,
        String model,
        String content,
        String finishReason,
        LlmUsage usage
) {

    public LlmChatCompletion {
        if (usage == null) {
            usage = LlmUsage.empty();
        }
    }

    public String contentOrEmpty() {
        return content == null ? "" : content;
    }
}
