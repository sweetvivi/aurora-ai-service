package com.hub.backend.modules.smartedit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resolution option used by the smart-editing model picker.
 */
@Schema(name = "SmartEditPhotoDpi", description = "修图模型清晰度档位")
public record PhotoDpiResponse(
        String key,
        String val,
        int points
) {
}
