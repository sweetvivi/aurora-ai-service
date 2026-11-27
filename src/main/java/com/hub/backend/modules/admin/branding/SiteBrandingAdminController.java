package com.hub.backend.modules.admin.branding;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.SiteBrandingService;
import com.hub.backend.modules.appconfig.dto.SiteBrandingHistoryResponse;
import com.hub.backend.modules.appconfig.dto.SiteBrandingRequest;
import com.hub.backend.modules.appconfig.dto.SiteBrandingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console entry for site chrome logos (login, sidenav, chat, …).
 *
 * <p>Site is resolved from the channel header / host — never from the body — same as other
 * {@code site-settings} endpoints.</p>
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "SITE_BRANDING")
@RestController
@RequestMapping("/api/admin/v1/site-settings/branding")
@Tag(name = "管理 · 站点品牌", description = "站点 Logo（统一默认 + 分位置覆盖）")
public class SiteBrandingAdminController {

    private final ChannelResolver channelResolver;
    private final SiteBrandingService siteBrandingService;
    private final AdminGuard adminGuard;

    public SiteBrandingAdminController(ChannelResolver channelResolver,
                                       SiteBrandingService siteBrandingService,
                                       AdminGuard adminGuard) {
        this.channelResolver = channelResolver;
        this.siteBrandingService = siteBrandingService;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    @Operation(summary = "读取站点 Logo 配置")
    public SiteBrandingResponse get(HttpServletRequest request) {
        adminGuard.require(request);
        return siteBrandingService.load(channelResolver.resolve(request));
    }

    @GetMapping("/history")
    @Operation(summary = "读取站点 Logo 变更历史",
            description = "按时间倒序返回最近 50 条；每条含槽位、旧地址、新地址与变更时间。")
    public SiteBrandingHistoryResponse history(HttpServletRequest request) {
        adminGuard.require(request);
        return siteBrandingService.history(channelResolver.resolve(request));
    }

    @PutMapping
    @Operation(summary = "保存站点 Logo 配置",
            description = """
                    一次写入全部槽位。传空字符串或 null 表示清除该位置覆盖，前台会回退到「统一默认」，
                    再回退到内置图。地址须为 `/media/` 相对路径或 HTTP(S) URL。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已保存"),
            @ApiResponse(responseCode = "400", description = "地址非法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SiteBrandingResponse update(@Valid @RequestBody SiteBrandingRequest body,
                                       HttpServletRequest request) {
        adminGuard.require(request);
        return siteBrandingService.update(channelResolver.resolve(request), body);
    }
}
