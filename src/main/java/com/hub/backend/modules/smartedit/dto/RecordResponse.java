package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Public representation of one smart-editing record.
 */
@Schema(name = "SmartEditRecord", description = "智能修图记录")
public record RecordResponse(
        Long id,
        String title,
        JsonNode canvas,
        JsonNode messages,
        String cover,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
