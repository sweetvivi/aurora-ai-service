package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Initialisation payload a client fetches before rendering the workspace.
 *
 * @param siteKey      resolved white label site key
 * @param appName      brand name shown by the client
 * @param domain       primary domain bound to the site, {@code null} when unbound
 * @param status       lifecycle state of the site
 * @param defaultModel model key used when the user has not selected one
 * @param navigation   global navigation entry visibility by nav key
 * @param navigationSections ordered navigation section titles and their item keys
 * @param features     feature switches, already merged with the server side defaults
 * @param announcement optional announcement rendered on the home page
 * @param logos        raw site chrome logo URLs; clients fall back slot → default → built-in
 * @param serverTime   server clock, clients use it to detect skewed device time
 */
@Schema(name = "AppConfigResponse", description = "站点初始化配置")
public record AppConfigResponse(
        @Schema(description = "站点标识", example = "default") String siteKey,
        @Schema(description = "站点展示名称（白标）", example = "Default Site") String appName,
        @Schema(description = "站点主域名，未绑定时为 null") String domain,
        @Schema(description = "站点状态", example = "ACTIVE") String status,
        @Schema(description = "默认模型 key", example = "deepseek-v4.1-flash") String defaultModel,
        @Schema(description = "前台导航入口是否显示，未配置时默认 true") Map<String, Boolean> navigation,
        @Schema(description = "前台导航标题、顺序与功能归属") List<NavigationSection> navigationSections,
        @Schema(description = "功能开关，已与服务端默认值合并") Map<String, Boolean> features,
        @Schema(description = "首页公告，未配置时为 null") String announcement,
        @Schema(description = "站点 Logo 原始配置（未配置槽位为 null）") SiteBrandingLogos logos,
        @Schema(description = "服务端时间") Instant serverTime
) {
}
