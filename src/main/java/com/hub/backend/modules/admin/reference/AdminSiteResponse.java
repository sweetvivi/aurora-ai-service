package com.hub.backend.modules.admin.reference;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One white label site, as a form option.
 *
 * <p>Exists so the model form can offer a select instead of asking an operator to type a site id
 * from memory — the same reason the model write path validates that a referenced id exists.</p>
 */
@Schema(name = "AdminSite", description = "站点条目（后台表单下拉用）")
public record AdminSiteResponse(
        @Schema(description = "站点 ID，写入模型时作为 siteId 提交", example = "1") Long id,
        @Schema(description = "站点 key，也是请求头 app-name 的取值", example = "default") String siteKey,
        @Schema(description = "展示名称") String displayName,
        @Schema(description = "绑定域名，可为 null") String domain,
        @Schema(description = "状态", example = "ACTIVE") String status,
        @Schema(description = "创建时间") LocalDateTime createdAt
) {

    public static AdminSiteResponse of(AppSiteEntity site) {
        return new AdminSiteResponse(
                site.getId(),
                site.getSiteKey(),
                site.getDisplayName(),
                site.getDomain(),
                site.getStatus(),
                site.getCreatedAt());
    }
}
