package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/smart-editing/records}.
 */
@Schema(name = "SmartEditRecordCreateRequest", description = "新建智能修图记录")
public record RecordCreateRequest(
        @Schema(description = "记录标题", example = "商品图精修")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Schema(description = "初始画布 JSON", example = "{\"images\":[],\"scale\":1}")
        JsonNode canvas,

        @Schema(description = "初始消息 JSON 数组", example = "[]")
        JsonNode messages
) {
}
