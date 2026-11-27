package com.hub.backend.modules.appconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The site's default chat model, as configured and as actually used.
 *
 * <p>Both values are returned on purpose. The stored key is a bare string that nobody validates at
 * write time in the past, so it can silently outlive the model it names — the catalogue gets
 * re-synced with dated keys, and the setting keeps pointing at a row that no longer exists. Reading
 * back only {@code configured} would show an operator a value that looks fine while every request
 * quietly falls back to something else.</p>
 *
 * @param configured     what is stored in {@code app_site_settings}; {@code null} means "not set,
 *                       follow the deployment default"
 * @param effective      the model key actually used when a request names none
 * @param fallbackUsed   true when {@code configured} is set but no longer usable, so
 *                       {@code effective} is a substitute the operator did not choose
 * @param configuredValid null when nothing is configured, otherwise whether the stored key still
 *                        resolves to an enabled chat model on this site
 */
@Schema(name = "SiteDefaultModelResponse", description = "站点默认对话模型（配置值与实际生效值）")
public record SiteDefaultModelResponse(
        @Schema(description = "站点标识") String siteKey,
        @Schema(description = "配置值；null 表示未配置，跟随部署默认") String configured,
        @Schema(description = "实际生效的模型 key") String effective,
        @Schema(description = "配置值已失效、当前生效值是回退来的") boolean fallbackUsed,
        @Schema(description = "配置值是否仍指向一个可用的对话模型") Boolean configuredValid) {
}
