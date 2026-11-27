package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/smart-editing/records/{id}/messages}.
 */
@Schema(name = "SmartEditMessagesUpdateRequest", description = "覆盖保存完整消息数组")
public record RecordMessagesUpdateRequest(
        @NotNull(message = "messages must not be null")
        JsonNode messages
) {
}
