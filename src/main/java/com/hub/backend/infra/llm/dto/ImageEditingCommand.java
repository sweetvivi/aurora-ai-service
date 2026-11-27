package com.hub.backend.infra.llm.dto;

import com.hub.backend.infra.llm.ProviderCredentials;

import java.util.List;

/**
 * One image-to-image request.
 */
public record ImageEditingCommand(
        String model,
        String prompt,
        List<ImageEditFile> images,
        ImageEditFile mask,
        String size,
        ProviderCredentials credentials
) {

    public ImageEditingCommand {
        images = images == null
                ? List.of()
                : images.stream()
                        .map(image -> image == null
                                ? new ImageEditFile(new byte[0], "image.png", "application/octet-stream")
                                : image)
                        .toList();
    }

    public ImageEditingCommand(String model,
                               String prompt,
                               ImageEditFile image,
                               ImageEditFile mask,
                               String size,
                               ProviderCredentials credentials) {
        this(model, prompt, image == null ? List.of() : List.of(image), mask, size, credentials);
    }

    /**
     * Kept for callers that still address the primary image directly.
     */
    public ImageEditFile image() {
        return images.isEmpty() ? null : images.get(0);
    }
}
