package com.hub.backend.modules.appconfig;

import com.hub.backend.common.ApiError;
import com.hub.backend.modules.appconfig.dto.AppConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site bootstrap configuration.
 */
@RestController
@RequestMapping("/api/v1/app")
@Tag(name = "应用配置", description = "站点识别与初始化配置下发")
public class AppConfigController {

    private final ChannelResolver channelResolver;
    private final AppConfigService appConfigService;

    public AppConfigController(ChannelResolver channelResolver, AppConfigService appConfigService) {
        this.channelResolver = channelResolver;
        this.appConfigService = appConfigService;
    }

    @Operation(
            summary = "获取站点初始化配置",
            description = """
                    客户端启动时调用，一次拿到白标名称、功能开关、公告与默认模型。

                    站点按 `app-name` 请求头 → 请求 Host 映射 → 服务端默认站点依次解析；
                    显式传入但无法识别的 `app-name` 返回 404，不会降级到其他站点。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "站点配置"),
            @ApiResponse(responseCode = "404", description = "指定的站点不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/config")
    public AppConfigResponse config(HttpServletRequest request) {
        return appConfigService.loadConfig(channelResolver.resolve(request));
    }
}
