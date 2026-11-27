package com.hub.backend.modules.admin.model;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.SiteDefaultModelService;
import com.hub.backend.modules.appconfig.dto.SiteDefaultModelRequest;
import com.hub.backend.modules.appconfig.dto.SiteDefaultModelResponse;
import com.hub.backend.common.ApiError;
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
 * The console entry for the site's default chat model.
 *
 * <p>Site comes from {@link ChannelResolver}, never from the request body — an admin key is scoped
 * to a deployment, and letting the payload name a site would let one console write another site's
 * configuration.</p>
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "SITE_DEFAULT_MODEL")
@RestController
@RequestMapping("/api/admin/v1/site-settings/default-model")
@Tag(name = "管理 · 模型", description = "站点默认对话模型（未指定模型时使用）")
public class SiteDefaultModelAdminController {

    private final ChannelResolver channelResolver;
    private final SiteDefaultModelService siteDefaultModelService;
    private final AdminGuard adminGuard;

    public SiteDefaultModelAdminController(ChannelResolver channelResolver,
                                           SiteDefaultModelService siteDefaultModelService,
                                           AdminGuard adminGuard) {
        this.channelResolver = channelResolver;
        this.siteDefaultModelService = siteDefaultModelService;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    @Operation(summary = "读取站点默认对话模型",
            description = """
                    同时返回配置值与实际生效值。两者不一致说明配置的 key 已不在目录里（或已停用），
                    系统正在用回退模型顶着 —— 这时 `fallbackUsed=true`、`configuredValid=false`。
                    """)
    public SiteDefaultModelResponse get(HttpServletRequest request) {
        adminGuard.require(request);
        return siteDefaultModelService.load(channelResolver.resolve(request));
    }

    @PutMapping
    @Operation(summary = "设置站点默认对话模型",
            description = """
                    传 `modelKey` 保存；传空值或 `null` 清除配置，回到部署默认（`app.llm.model`）。

                    写入前校验该 key 在当前站点可见、类型为 CHAT、状态为启用 —— 存一个目录里没有的
                    key 会让所有「未指定模型」的请求变成 404，而回退逻辑只会打一条日志，界面上看不出来。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已保存，返回新的配置值与生效值"),
            @ApiResponse(responseCode = "400", description = "模型不可用或不是对话模型",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SiteDefaultModelResponse update(@Valid @RequestBody SiteDefaultModelRequest body,
                                           HttpServletRequest request) {
        adminGuard.require(request);
        return siteDefaultModelService.update(channelResolver.resolve(request), body.modelKey());
    }
}
