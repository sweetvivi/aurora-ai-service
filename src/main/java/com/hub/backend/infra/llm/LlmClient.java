package com.hub.backend.infra.llm;

import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;

import java.util.function.Consumer;

/**
 * Port for the upstream model gateway.
 *
 * <p>Business modules depend on this interface only, never on a concrete provider or on the
 * OpenAI wire format.</p>
 */
public interface LlmClient {

    /**
     * Performs a blocking chat completion.
     *
     * @throws com.hub.backend.common.exception.LlmInvocationException when the gateway is
     *         unreachable, rejects the request or answers with an unusable body
     */
    LlmChatCompletion complete(LlmChatCommand command);

    /**
     * Performs a streaming chat completion, invoking {@code onChunk} for every increment.
     *
     * <p>The callback runs on the calling thread, so it must not block. Consequences of a failure
     * are surfaced as {@link com.hub.backend.common.exception.LlmInvocationException}.</p>
     */
    void stream(LlmChatCommand command, Consumer<LlmStreamChunk> onChunk);
}
