package com.hub.backend.infra.llm.dto;

/**
 * One produced image, in whichever shape the gateway used.
 *
 * <p>Gateways differ here: some return inline base64, others a (often short lived) URL. At least
 * one of the two is present.</p>
 *
 * @param base64        inline image bytes, base64 encoded, may be {@code null}
 * @param url           gateway hosted image URL, may be {@code null}
 * @param revisedPrompt prompt as the gateway actually used, may be {@code null}
 */
public record GeneratedImage(String base64, String url, String revisedPrompt) {

    public boolean hasInlineData() {
        return base64 != null && !base64.isBlank();
    }

    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }
}