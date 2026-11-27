package com.hub.backend.modules.smartedit.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/smart-editing/retouch}.
 */
@Schema(name = "SmartEditRetouchRequest", description = "智能修图对话出图")
public record RetouchRequest(
        @NotNull(message = "messages must not be null")
        JsonNode messages,

        @Schema(description = "前端展示用模型标识") @Size(max = 255) String model,

        @Schema(description = "平台模型 key，优先使用该值") @Size(max = 128) String modelKey,

        @Schema(description = "清晰度或尺寸档位", example = "2k") @Size(max = 32) String size,

        @Schema(description = "是否保存记录；当前出图结果统一保存为附件") Boolean save
) {
}
