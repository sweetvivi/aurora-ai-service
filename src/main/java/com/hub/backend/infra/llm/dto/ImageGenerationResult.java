package com.hub.backend.infra.llm.dto;

import java.util.List;

/**
 * Result of one image generation call.
 *
 * @param createdEpochSecond gateway timestamp, seconds since the epoch
 * @param model              model that actually served the request
 * @param images             produced images, never empty
 */
public record ImageGenerationResult(long createdEpochSecond, String model, List<GeneratedImage> images) {
}