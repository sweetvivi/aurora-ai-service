package com.hub.backend.modules.image.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Result of {@code POST /api/v1/images/generations}.
 *
 * @param created generation time reported by the gateway
 * @param model   model that served the request
 * @param images  produced images
 */
@Schema(name = "ImageGenerationResponse", description = "图片生成结果")
public record ImageGenerationResponse(
        @Schema(description = "生成时间") Instant created,
        @Schema(description = "实际使用的图像模型", example = "gpt-image-2") String model,
        @Schema(description = "生成张数", example = "1") int count,
        @Schema(description = "图片列表") List<GeneratedImageResponse> images
) {
}