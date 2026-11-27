package com.hub.backend.modules.admin.model;

import com.hub.backend.common.ApiError;
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
 * Admin console for operational model brands (vendor icons / names / sort).
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "LLM_MODEL_BRAND")
@RestController
@RequestMapping("/api/admin/v1/model-brands")
@Tag(name = "管理 · 模型厂商", description = "模型厂商（品牌）的增删改查与启停；不影响 New API 同步")
public class LlmModelBrandAdminController {

    private final LlmModelBrandAdminService brandAdminService;
    private final AdminGuard adminGuard;

    public LlmModelBrandAdminController(LlmModelBrandAdminService brandAdminService,
                                        AdminGuard adminGuard) {
        this.brandAdminService = brandAdminService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "厂商列表",
            description = "按 sortOrder ASC, id ASC 返回。linkedModelCount 按 company 匹配统计。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "厂商列表"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public List<AdminModelBrandResponse> list(
            @Parameter(description = "状态筛选：ACTIVE / DISABLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "匹配 brandKey / displayName / description")
            @RequestParam(required = false) String keyword,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return brandAdminService.list(status, keyword);
    }

    @Operation(summary = "创建厂商")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "已创建"),
            @ApiResponse(responseCode = "400", description = "字段校验失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "brandKey 已存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<AdminModelBrandResponse> create(
            @Valid @RequestBody AdminModelBrandRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(brandAdminService.create(request));
    }

    @Operation(summary = "厂商详情")
    @GetMapping("/{id}")
    public AdminModelBrandResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return brandAdminService.detail(id);
    }

    @Operation(summary = "更新厂商")
    @PutMapping("/{id}")
    public AdminModelBrandResponse update(@PathVariable Long id,
                                          @Valid @RequestBody AdminModelBrandRequest request,
                                          HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return brandAdminService.update(id, request);
    }

    @Operation(summary = "启停厂商")
    @PutMapping("/{id}/status")
    public AdminModelBrandResponse updateStatus(@PathVariable Long id,
                                                @Valid @RequestBody AdminModelBrandStatusRequest request,
                                                HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return brandAdminService.updateStatus(id, request);
    }

    @Operation(summary = "删除厂商",
            description = "只删品牌装饰，不删模型；模型会退回无图标的派生家族。")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        brandAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
