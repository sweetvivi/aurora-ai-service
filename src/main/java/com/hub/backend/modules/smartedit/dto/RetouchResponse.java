package com.hub.backend.modules.smartedit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of one smart-editing conversation turn.
 */
@Schema(name = "SmartEditRetouchResponse", description = "智能修图对话结果")
public record RetouchResponse(
        String content,
        List<RetouchImageResponse> images
) {
}
