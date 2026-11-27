package com.hub.backend.modules.appconfig.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Admin write body for site logos. Empty string or null clears that slot.
 */
@Schema(name = "SiteBrandingRequest", description = "站点 Logo 配置写入")
public record SiteBrandingRequest(
        @JsonProperty("default")
        @Size(max = 2048)
        @Schema(description = "统一默认 Logo；空字符串表示清除")
        String defaultLogo,
        @Size(max = 2048)
        @Schema(description = "登录 / 注册页；空字符串表示清除")
        String auth,
        @Size(max = 2048)
        @Schema(description = "侧栏品牌；空字符串表示清除")
        String sidenav,
        @Size(max = 2048)
        @Schema(description = "对话欢迎区与移动顶栏；空字符串表示清除")
        String chat,
        @Size(max = 2048)
        @Schema(description = "对话助手头像；空字符串表示清除")
        String assistant,
        @Size(max = 2048)
        @Schema(description = "功能提示 Toast；空字符串表示清除")
        String toast
) {
}
