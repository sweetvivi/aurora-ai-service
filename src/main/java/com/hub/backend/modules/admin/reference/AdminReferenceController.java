package com.hub.backend.modules.admin.reference;

import com.hub.backend.modules.admin.provider.AdminGuard;
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

import java.util.List;

/**
 * The reference lists the console needs to render selects.
 *
 * <p>Both endpoints are read-only and sit behind the same admin key as the rest of
 * {@code /api/admin/v1}.</p>
 */
@RestController
@RequestMapping("/api/admin/v1")
@Tag(name = "管理 · 参考数据", description = "后台表单需要的只读列表（站点、模型分组）")
public class AdminReferenceController {

    private final AdminReferenceService referenceService;
    private final AdminGuard adminGuard;

    public AdminReferenceController(AdminReferenceService referenceService, AdminGuard adminGuard) {
        this.referenceService = referenceService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "站点列表",
            description = """
                    写入模型时的 `siteId` 可选项。`siteId` 留空表示全局模型，本站点可见。

                    返回全部站点（含停用），因为运维管理的是整个平台，停用站点里的模型仍需维护。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "站点列表"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = com.hub.backend.common.ApiError.class)))
    })
    @GetMapping("/sites")
    public List<AdminSiteResponse> sites(HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return referenceService.sites();
    }

    @Operation(summary = "模型分组列表",
            description = """
                    写入模型时的 `groupId` 可选项。留空表示不分组——这类模型仍会出现在公开目录里，
                    只是没有 `groupKey`。

                    返回全部站点可见的分组之外的全部行：运维填的是平台的模型，不应受单个站点的可见性限制。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "分组列表"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = com.hub.backend.common.ApiError.class)))
    })
    @GetMapping("/model-groups")
    public List<AdminModelGroupResponse> modelGroups(HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return referenceService.modelGroups();
    }
}
