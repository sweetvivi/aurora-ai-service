package com.hub.backend.infra.llm;

import com.hub.backend.infra.llm.dto.ImageEditingCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;

/**
 * Port for upstream image editing / image-to-image calls.
 */
public interface ImageEditingClient {

    /**
     * @throws com.hub.backend.common.exception.LlmInvocationException when the gateway is
     *         unreachable, rejects the request, or answers without a usable image
     */
    ImageGenerationResult edit(ImageEditingCommand command);
}
