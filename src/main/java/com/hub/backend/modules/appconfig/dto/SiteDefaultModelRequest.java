package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Writes the site's default chat model.
 *
 * @param modelKey model key to store; {@code null} or blank clears the setting so the site falls
 *                 back to the deployment default
 */
@Schema(name = "SiteDefaultModelRequest", description = "设置站点默认对话模型")
public record SiteDefaultModelRequest(
        @Schema(description = "模型 key；留空表示清除配置、跟随部署默认", example = "deepseek-v4.1-flash")
        @Size(max = 128, message = "modelKey must be at most 128 characters")
        String modelKey) {
}
