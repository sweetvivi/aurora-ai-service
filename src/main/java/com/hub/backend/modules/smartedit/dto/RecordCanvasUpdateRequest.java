package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/smart-editing/records/{id}/canvas}.
 */
@Schema(name = "SmartEditCanvasUpdateRequest", description = "覆盖保存画布")
public record RecordCanvasUpdateRequest(
        @NotNull(message = "canvas must not be null")
        JsonNode canvas
) {
}
