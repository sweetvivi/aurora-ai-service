package com.hub.backend.infra.llm.dto;

/**
 * Binary image supplied to a multimodal chat model.
 */
public record LlmImageAttachment(String mimeType, byte[] content) {

    public LlmImageAttachment {
        content = content == null ? new byte[0] : content.clone();
    }
}
