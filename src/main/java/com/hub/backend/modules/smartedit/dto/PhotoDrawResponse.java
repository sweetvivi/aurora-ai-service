package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Drawing metadata in the snake_case shape expected by the frontend adapter.
 */
@Schema(name = "SmartEditPhotoDraw", description = "修图模型绘制参数")
public record PhotoDrawResponse(
        List<String> size,
        List<PhotoDpiResponse> dpi,
        @JsonProperty("custom_size_min") int customSizeMin,
        @JsonProperty("custom_size_max") int customSizeMax,
        @JsonProperty("image_max") int imageMax,
        @JsonProperty("max_number") int maxNumber
) {
}
