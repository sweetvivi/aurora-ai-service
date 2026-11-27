package com.hub.backend.modules.admin.commerce;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
 * 电商工具的后台维护接口。
 *
 * <p>与前台 {@code /api/v1/commerce/catalog} 共用同一张 {@code commerce_tools} 表：
 * 前台按 {@code status=ACTIVE} + {@code sort_order} 分组下发，这里则能看到全部状态并直接改。
 * 改完无需重启，前台刷新即生效。</p>
 *
 * <p>鉴权沿用管理面统一约定：请求头 {@code X-Admin-Key} 必须等于 {@code app.admin.api-key}。</p>
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "COMMERCE_TOOL")
@RestController
@RequestMapping("/api/admin/v1/commerce-tools")
@Tag(name = "电商工具管理", description = "后台电商工具增删改查与表单 schema 维护")
public class CommerceToolAdminController {

    private final CommerceToolAdminService service;
    private final AdminGuard adminGuard;

    public CommerceToolAdminController(CommerceToolAdminService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "工具列表",
            description = """
                    后台列表默认同时返回 ACTIVE 与 DELETED，便于下架后恢复。
                    `keyword` 会匹配名称、toolKey、分类与简介（不区分大小写）。
                    `sort` 可选 sort（默认，按排序号）/ updated / created / name。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "工具分页列表"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public PageResponse<CommerceToolAdminResponse> list(
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 200") @RequestParam(required = false) Integer size,
            @Parameter(description = "站点 ID") @RequestParam(required = false) Long siteId,
            @Parameter(description = "场景：commerce / painting；不传表示全部")
            @RequestParam(required = false) String scene,
            @Parameter(description = "分类名称") @RequestParam(required = false) String category,
            @Parameter(description = "状态：ACTIVE / DELETED；不传表示全部")
            @RequestParam(required = false) String status,
            @Parameter(description = "名称 / toolKey / 分类 / 简介关键字")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "排序：sort（默认）、updated、created、name")
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.list(page, size, siteId, scene, category, status, keyword, sort);
    }

    @Operation(summary = "分类下拉",
            description = """
                    按排序号首次出现的顺序返回去重后的分类，含已删除条目，供筛选与新增时选择。

                    传 `scene` 则只返回该场景的分类：电商与绘画共用一张表，运营在「绘画工具」
                    页面看到 15 个电商分类只会添乱。不传仍然是全部（兼容旧调用）。
                    """)
    @GetMapping("/categories")
    public List<String> categories(@RequestParam(required = false) Long siteId,
                                   @Parameter(description = "场景：commerce / painting；不传表示全部")
                                   @RequestParam(required = false) String scene,
                                   HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.categories(siteId, scene);
    }

    @Operation(summary = "工具详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "工具详情"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "工具不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public CommerceToolAdminResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.detail(id);
    }

    @Operation(summary = "创建工具",
            description = """
                    `toolKey`、`category`、`name`、`form` 必填；`form` 限定为
                    background / pattern / placeholder / replace / retouch / try-on 之一。
                    同一站点下 toolKey 不可重复。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "已创建"),
            @ApiResponse(responseCode = "400", description = "字段校验失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "toolKey 已存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<CommerceToolAdminResponse> create(
            @RequestBody CommerceToolAdminRequest request, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "更新工具",
            description = """
                    按字段更新：请求中为 null 的字段保持原值。
                    `formSchema` / `uploadRoles` 传 `{}`、`retouchModes` 传 `[]` 表示清空该列。
                    `toolKey` 不可修改，传不同值会返回 400。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已更新"),
            @ApiResponse(responseCode = "400", description = "字段校验失败或试图修改 toolKey",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "工具不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}")
    public CommerceToolAdminResponse update(@PathVariable Long id,
                                            @RequestBody CommerceToolAdminRequest request,
                                            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.update(id, request);
    }

    @Operation(summary = "删除工具", description = "软删除：置为 DELETED，前台不再下发，后台仍可查询与恢复。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "工具不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "彻底删除工具",
            description = "物理删行，不可恢复。要求工具处于 DELETED（已下架）状态，否则返回 400。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已彻底删除"),
            @ApiResponse(responseCode = "400", description = "工具尚未下架",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "工具不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}/purge")
    public ResponseEntity<Void> purge(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        service.purge(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "复制工具",
            description = "把提示词、表单 schema、案例图整份复制成新工具，toolKey 自动加 -copy 后缀并去重。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "已复制"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "源工具不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<CommerceToolAdminResponse> duplicate(@PathVariable Long id,
                                                               HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(id));
    }
}
