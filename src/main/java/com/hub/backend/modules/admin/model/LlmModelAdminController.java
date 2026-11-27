package com.hub.backend.modules.admin.model;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
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
 * Admin catalogue view. New API owns model creation, deletion and routing; this controller only
 * exposes reads plus local visibility/display controls.
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "LLM_MODEL")
@RestController
@RequestMapping("/api/admin/v1/models")
@Tag(name = "模型管理", description = "New API 模型目录、显示状态和展示字段")
public class LlmModelAdminController {

    private final LlmModelAdminService modelAdminService;
    private final AdminGuard adminGuard;

    public LlmModelAdminController(LlmModelAdminService modelAdminService,
                                   AdminGuard adminGuard) {
        this.modelAdminService = modelAdminService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "同步 New API 模型目录",
            description = "重新读取 New API 的渠道和模型，本地只更新缓存、显示状态不会被覆盖。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "同步完成"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "New API 不可用或管理令牌未配置",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @AuditLog(action = "SYNC_MODEL_CATALOG", target = "LLM_MODEL")
    @PostMapping("/sync")
    public NewApiCatalogSyncResponse sync(HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.sync();
    }

    @Operation(summary = "模型清单",
            description = "返回 New API 同步到本地的全部模型，包含隐藏和上游已下线的模型。")
    @GetMapping
    public List<AdminModelResponse> list(
            @Parameter(description = "按类型筛选：CHAT / IMAGE / VIDEO / AUDIO")
            @RequestParam(required = false) String type,
            @Parameter(description = "按本地显示状态筛选：ACTIVE / DISABLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "按上游可用性筛选：ONLINE 可用 / OFFLINE 已下线")
            @RequestParam(required = false) String availability,
            @Parameter(description = "按供应商 ID 筛选")
            @RequestParam(required = false) Long providerId,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.list(type, status, availability, providerId);
    }

    @Operation(summary = "模型详情")
    @GetMapping("/{id}")
    public AdminModelResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.detail(id);
    }

    @Operation(summary = "批量修改模型",
            description = "一次事务内修改多个模型的显隐与展示字段；任一 modelId 不存在则整体回滚。"
                    + "返回更新后的完整行，前端可原地合并，无需重拉列表。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "全部写入成功"),
            @ApiResponse(responseCode = "400", description = "items 为空、超过 100 条、字段非法或 modelId 重复",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "任一 modelId 不存在，整体回滚",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @AuditLog(action = "BATCH_UPDATE_MODEL", target = "LLM_MODEL")
    @PutMapping("/batch")
    public AdminModelBatchUpdateResponse batchUpdate(
            @Valid @RequestBody AdminModelBatchUpdateRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.batchUpdate(request);
    }

    @Operation(summary = "修改模型显示状态",
            description = "只改变本地显示/隐藏，不影响 New API 中的模型和计费。")
    @AuditLog(action = "UPDATE_MODEL_VISIBILITY", target = "LLM_MODEL", id = "#id",
            before = "@modelAuditProbes.snapshot(#id)")
    @PutMapping("/{id}/visibility")
    public AdminModelResponse updateVisibility(@PathVariable Long id,
                                               @Valid @RequestBody AdminModelVisibilityRequest request,
                                               HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.updateVisibility(id, request);
    }

    @Operation(summary = "修改模型展示字段",
            description = "允许修改展示名称、厂商、图标、说明、排序号，以及本地能力标记"
                    + "（thinking / thinkingParameter / supportsWebSearch）；"
                    + "VIDEO 模型还可写 videoCapabilities（modes / 画幅 / 分辨率 / 时长等）；"
                    + "modelKey、route、供应商由 New API 同步。")
    @AuditLog(action = "UPDATE_MODEL_DISPLAY", target = "LLM_MODEL", id = "#id",
            before = "@modelAuditProbes.snapshot(#id)")
    @PutMapping("/{id}/display")
    public AdminModelResponse updateDisplay(@PathVariable Long id,
                                            @Valid @RequestBody AdminModelDisplayRequest request,
                                            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.updateDisplay(id, request);
    }

    @Operation(summary = "删除已下线模型",
            description = "物理删行，只允许删除 upstreamAvailable=false 的模型；仍在上游提供的返回 409。"
                    + "会连带清理标签、智能体绑定、会话分组成员和版本组成员关系，"
                    + "并把历史会话/消息/用量上的模型引用置空（记录本身保留）。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "模型不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "模型上游仍可用，拒绝删除",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @AuditLog(action = "DELETE_MODEL", target = "LLM_MODEL", id = "#id",
            before = "@modelAuditProbes.snapshot(#id)", recordResult = false)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        modelAdminService.deleteOfflineModel(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "批量清理已下线模型",
            description = "部分成功语义：仍在上游提供的模型与已不存在的 id 计入 skipped，其余照常删除。"
                    + "重复提交同一批 id 是幂等的。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "处理完成，详见 deleted / skipped"),
            @ApiResponse(responseCode = "400", description = "modelIds 为空或超过 100 条",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @AuditLog(action = "PURGE_OFFLINE_MODELS", target = "LLM_MODEL")
    @PostMapping("/batch-delete")
    public AdminModelPurgeResponse batchDelete(
            @Valid @RequestBody AdminModelPurgeRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return modelAdminService.purgeOfflineModels(request);
    }
}
