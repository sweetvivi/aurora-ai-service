package com.hub.backend.infra.llm.dto;

/**
 * Token accounting reported by the provider.
 */
public record LlmUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    private static final LlmUsage EMPTY = new LlmUsage(0, 0, 0);

    /** Provider that omit usage on this call report zero tokens. */
    public static LlmUsage empty() {
        return EMPTY;
    }

    public int promptTokensOrZero() {
        return promptTokens == null ? 0 : promptTokens;
    }

    public int completionTokensOrZero() {
        return completionTokens == null ? 0 : completionTokens;
    }

    public int totalTokensOrZero() {
        return totalTokens == null ? 0 : totalTokens;
    }

    public LlmUsage merge(LlmUsage other) {
        if (other == null) {
            return this;
        }
        int mergedPrompt = Math.max(promptTokensOrZero(), other.promptTokensOrZero());
        int mergedCompletion = Math.max(
                completionTokensOrZero(), other.completionTokensOrZero());
        int reportedTotal = Math.max(totalTokensOrZero(), other.totalTokensOrZero());
        int mergedTotal = Math.max(reportedTotal, mergedPrompt + mergedCompletion);
        return new LlmUsage(mergedPrompt, mergedCompletion, mergedTotal);
    }
}
