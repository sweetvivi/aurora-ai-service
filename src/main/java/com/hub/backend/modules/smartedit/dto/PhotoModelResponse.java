package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Model shape consumed by {@code src/api/smartEditing.ts}.
 */
@Schema(name = "SmartEditPhotoModel", description = "智能修图模型")
public record PhotoModelResponse(
        String key,
        String name,
        String model,
        String company,
        String icon,
        @JsonProperty("consume_points") BigDecimal consumePoints,
        PhotoDrawResponse draw,
        PhotoModelFlagsResponse other
) {
}
