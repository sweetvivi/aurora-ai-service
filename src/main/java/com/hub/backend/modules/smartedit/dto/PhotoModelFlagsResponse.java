package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Compatibility flags understood by the frontend model adapter.
 */
@Schema(name = "SmartEditPhotoModelFlags", description = "修图模型能力标记")
public record PhotoModelFlagsResponse(
        @JsonProperty("photoshop_model") boolean photoshopModel
) {
}
