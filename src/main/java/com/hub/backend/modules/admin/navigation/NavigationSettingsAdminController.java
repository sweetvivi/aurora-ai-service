package com.hub.backend.modules.admin.navigation;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.NavigationSettingsService;
import com.hub.backend.modules.appconfig.dto.NavigationSettingsRequest;
import com.hub.backend.modules.appconfig.dto.NavigationSettingsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "SITE_NAVIGATION")
@RestController
@RequestMapping("/api/admin/v1/navigation-settings")
@Tag(name = "管理 · 前台页面", description = "前台全局导航入口显示控制")
public class NavigationSettingsAdminController {

    private final ChannelResolver channelResolver;
    private final NavigationSettingsService navigationSettingsService;
    private final AdminGuard adminGuard;

    public NavigationSettingsAdminController(ChannelResolver channelResolver,
                                             NavigationSettingsService navigationSettingsService,
                                             AdminGuard adminGuard) {
        this.channelResolver = channelResolver;
        this.navigationSettingsService = navigationSettingsService;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    @Operation(summary = "读取前台导航入口显示设置")
    public NavigationSettingsResponse get(HttpServletRequest request) {
        adminGuard.require(request);
        return navigationSettingsService.load(channelResolver.resolve(request));
    }

    @PutMapping
    @Operation(summary = "保存前台导航入口显示设置")
    public NavigationSettingsResponse update(@Valid @RequestBody NavigationSettingsRequest body,
                                             HttpServletRequest request) {
        adminGuard.require(request);
        return navigationSettingsService.update(
                channelResolver.resolve(request),
                body.navigation(),
                body.navigationSections());
    }
}
