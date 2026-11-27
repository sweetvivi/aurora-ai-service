package com.hub.backend.infra.llm.dto;

import com.hub.backend.infra.llm.ProviderCredentials;

import java.util.List;

/**
 * One video generation request forwarded to a supplier.
 *
 * @param model            upstream model key
 * @param prompt           text prompt
 * @param mode             generation mode: {@code text2video}, {@code image2video},
 *                         {@code first-last-frame} or {@code reference2video}; {@code null}
 *                         when the caller did not state one
 * @param aspectRatio      e.g. {@code 16:9}, may be {@code null}
 * @param resolution       e.g. {@code 720p}, may be {@code null}
 * @param durationSeconds  requested clip length, may be {@code null}
 * @param referenceImage   first-frame image as a {@code data:} URL (or other upstream-readable
 *                         form); may be {@code null}. Used by image2video / first-last-frame.
 * @param lastFrameImage   last-frame image as a {@code data:} URL; may be {@code null}
 * @param referenceImages  subject/style reference images as {@code data:} URLs for
 *                         {@code reference2video}; empty or {@code null} when unused. Mutually
 *                         exclusive with {@code referenceImage}/{@code lastFrameImage}.
 * @param credentials      supplier credentials, {@code null} to use the service defaults
 */
public record VideoGenerationCommand(
        String model,
        String prompt,
        String mode,
        String aspectRatio,
        String resolution,
        Integer durationSeconds,
        String referenceImage,
        String lastFrameImage,
        List<String> referenceImages,
        ProviderCredentials credentials) {
}
