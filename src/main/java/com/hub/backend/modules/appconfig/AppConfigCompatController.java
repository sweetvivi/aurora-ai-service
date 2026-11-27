package com.hub.backend.modules.appconfig;

import com.hub.backend.modules.appconfig.dto.AppConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility adapter for the legacy initialisation path.
 *
 * <p>The public site calls {@code POST /api/v2/config/init} without a body. It is a thin mapping
 * onto {@link AppConfigService}, so both versions share one implementation and one response
 * contract.</p>
 */
@RestController
@RequestMapping("/api/v2/config")
@Tag(name = "应用配置", description = "站点识别与初始化配置下发")
public class AppConfigCompatController {

    private final ChannelResolver channelResolver;
    private final AppConfigService appConfigService;

    public AppConfigCompatController(ChannelResolver channelResolver, AppConfigService appConfigService) {
        this.channelResolver = channelResolver;
        this.appConfigService = appConfigService;
    }

    @Operation(
            summary = "站点初始化配置（旧版路径）",
            description = "兼容线上 `POST /api/v2/config/init`，响应与 `GET /api/v1/app/config` 完全一致。")
    @PostMapping("/init")
    public AppConfigResponse init(HttpServletRequest request) {
        return appConfigService.loadConfig(channelResolver.resolve(request));
    }
}
