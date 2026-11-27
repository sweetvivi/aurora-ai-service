package com.hub.backend.modules.appconfig.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Raw logo URLs stored for a site. Missing slots stay {@code null}; the client falls back
 * to {@code default}, then to its built-in asset.
 */
@Schema(name = "SiteBrandingLogos", description = "站点各位置 Logo 地址（未配置为 null）")
public record SiteBrandingLogos(
        @JsonProperty("default")
        @Schema(description = "统一默认 Logo；其它位置未单独配置时使用",
                example = "/media/commerce/logo.png")
        String defaultLogo,
        @Schema(description = "登录 / 注册页", example = "/media/commerce/auth.png")
        String auth,
        @Schema(description = "侧栏品牌（模块列表上方）", example = "/media/commerce/sidenav.png")
        String sidenav,
        @Schema(description = "对话欢迎区与移动顶栏", example = "/media/commerce/chat.png")
        String chat,
        @Schema(description = "对话助手头像", example = "/media/commerce/assistant.png")
        String assistant,
        @Schema(description = "功能提示 Toast 图标", example = "/media/commerce/toast.png")
        String toast
) {
    public static SiteBrandingLogos empty() {
        return new SiteBrandingLogos(null, null, null, null, null, null);
    }
}
