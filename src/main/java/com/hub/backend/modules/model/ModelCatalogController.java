package com.hub.backend.modules.model;

import com.hub.backend.common.ApiError;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.model.dto.ModelCatalogResponse;
import com.hub.backend.modules.model.dto.ModelFamilyCatalogResponse;
import com.hub.backend.modules.model.dto.ModelSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public model catalogue.
 *
 * <p>Readable without authentication because the site presents the model list before sign in, and
 * it exposes display data only: the upstream {@code route} never appears in a response.</p>
 */
@RestController
@RequestMapping("/api/v1/models")
@Tag(name = "模型目录", description = "可用模型、分组与消耗说明")
public class ModelCatalogController {

    private final ModelCatalogService modelCatalogService;
    private final ChannelResolver channelResolver;

    public ModelCatalogController(ModelCatalogService modelCatalogService, ChannelResolver channelResolver) {
        this.modelCatalogService = modelCatalogService;
        this.channelResolver = channelResolver;
    }

    @Operation(summary = "模型目录",
            description = """
                    返回当前站点可见的同版本模型组。每个条目代表一个版本组（例如 GPT-Image-2.0），
                    不暴露组内具体模型或上游 route。

                    可见性规则：全局版本组对所有站点可见，站点版本组会替换同 key 的全局组；
                    停用版本组、没有有效成员的版本组，以及未加入任何版本组的模型都不返回。

                    `type` 用于按用途筛选：对话选择器传 `CHAT`，图片面板传 `IMAGE`。
                    """)
    @ApiResponse(responseCode = "200", description = "模型目录")
    @GetMapping
    public ModelCatalogResponse catalog(
            @Parameter(description = "只看某个同版本模型组，例如 gpt-image-2.0")
            @RequestParam(required = false) String group,
            @Parameter(description = "按类型筛选：CHAT / IMAGE / VIDEO / AUDIO")
            @RequestParam(required = false) String type,
            HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return modelCatalogService.catalog(site.getId(), group, type);
    }

    @Operation(summary = "模型家族",
            description = """
                    把可见同版本模型组按**厂商**归组，供模型选择器直接使用。

                    与 `GET /api/v1/models` 的 `groups` 不同：`groups` 是按同版本模型归组，
                    家族是厂商维度（例如「DeepSeek」），由目录派生而非独立的表。
                    客户端不必再自行从 groups + models 组装。

                    传 `type` 时只归组该类型的模型：对话选择器用 `?type=CHAT`、绘画面板用
                    `?type=IMAGE`，两边不会互相混入；不传则与主列表一样返回全部可见模型。
                    """)
    @ApiResponse(responseCode = "200", description = "模型家族目录")
    @GetMapping("/families")
    public ModelFamilyCatalogResponse families(
            @Parameter(description = "按类型筛选：CHAT / IMAGE / VIDEO / AUDIO。"
                    + "与 `GET /api/v1/models` 的 `type` 语义一致；不传则返回全部类型。")
            @RequestParam(required = false) String type,
            HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return modelCatalogService.families(site.getId(), type);
    }

    @Operation(summary = "模型详情",
            description = "按 `modelKey` 查询单个模型，可见性与列表接口一致。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "模型条目"),
            @ApiResponse(responseCode = "404", description = "模型不存在或未在当前站点启用",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{modelKey}")
    public ModelSummaryResponse detail(@PathVariable String modelKey, HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return modelCatalogService.model(site.getId(), modelKey);
    }
}
