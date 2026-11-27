package com.hub.backend.infra.llm;

import com.hub.backend.infra.llm.dto.ImageGenerationCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;

/**
 * Port for image generation on the upstream gateway.
 *
 * <p>Kept separate from {@link LlmClient} on purpose: images use their own credentials, their own
 * timeout and their own response shape, so folding them into the chat port would drag two
 * configurations into every chat call site.</p>
 */
public interface ImageGenerationClient {

    /**
     * @throws com.hub.backend.common.exception.LlmInvocationException when the gateway is
     *         unreachable, rejects the request, or answers without a usable image
     */
    ImageGenerationResult generate(ImageGenerationCommand command);
}