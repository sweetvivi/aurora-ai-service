package com.hub.backend.modules.smartedit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Image URL accepted by matting and enhancement operations.
 */
@Schema(name = "SmartEditImageUrlRequest", description = "智能修图图片地址")
public record ImageUrlRequest(
        @NotBlank(message = "image_url must not be blank")
        @Size(max = 4096, message = "image_url must be at most 4096 characters")
        @com.fasterxml.jackson.annotation.JsonProperty("image_url")
        String imageUrl
) {
}
