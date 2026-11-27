package com.hub.backend.modules.admin.banner;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.banner.BannerService;
import com.hub.backend.modules.banner.dto.BannerRequest;
import com.hub.backend.modules.banner.dto.BannerResponse;
import com.hub.backend.modules.banner.dto.BannerStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin console endpoints for the top-of-page campaign banner.
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "BANNER")
@RestController
@RequestMapping("/api/admin/v1/banners")
@Tag(name = "管理 · Banner", description = "顶部活动 Banner 的增删改查与启停")
public class BannerAdminController {

    private final BannerService bannerService;
    private final AdminGuard adminGuard;

    public BannerAdminController(BannerService bannerService, AdminGuard adminGuard) {
        this.bannerService = bannerService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "Banner 列表",
            description = """
                    返回全部 Banner，按 `sortOrder ASC, id ASC` 排序。

                    `siteId=0` 表示只查询全局 Banner；其他数值只查询指定站点；
                    不传 `siteId` 时不过滤站点。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banner 列表"),
            @ApiResponse(responseCode = "400", description = "筛选参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public List<BannerResponse> list(
            @Parameter(description = "状态筛选：ACTIVE / DISABLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "站点筛选；0 表示只查全局")
            @RequestParam(required = false) Long siteId,
            @Parameter(description = "匹配 name / title / content")
            @RequestParam(required = false) String keyword,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return bannerService.list(status, siteId, keyword);
    }

    @Operation(summary = "创建 Banner")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "已创建"),
            @ApiResponse(responseCode = "400", description = "字段校验失败或站点不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<BannerResponse> create(@Valid @RequestBody BannerRequest request,
                                                 HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(bannerService.create(request));
    }

    @Operation(summary = "Banner 详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Banner 详情"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Banner 不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public BannerResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return bannerService.detail(id);
    }

    @Operation(summary = "整体更新 Banner")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已更新"),
            @ApiResponse(responseCode = "400", description = "字段校验失败或站点不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Banner 不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}")
    public BannerResponse update(@PathVariable Long id,
                                 @Valid @RequestBody BannerRequest request,
                                 HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return bannerService.update(id, request);
    }

    @Operation(summary = "删除 Banner")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Banner 不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        bannerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "更新 Banner 启停状态")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已更新"),
            @ApiResponse(responseCode = "400", description = "状态不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Banner 不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}/status")
    public BannerResponse updateStatus(@PathVariable Long id,
                                       @Valid @RequestBody BannerStatusRequest request,
                                       HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return bannerService.updateStatus(id, request);
    }
}
