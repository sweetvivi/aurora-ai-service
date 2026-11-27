package com.hub.backend.modules.admin.model;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "MODEL_FALLBACK")
@RestController
@RequestMapping("/api/admin/v1/model-fallbacks")
@Tag(name = "模型降级管理",
        description = "管理前台版本组、组内模型顺序和可参与降级的具体模型")
public class ModelFallbackAdminController {

    private final ModelFallbackAdminService service;
    private final AdminGuard adminGuard;

    public ModelFallbackAdminController(ModelFallbackAdminService service,
                                        AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "版本组列表")
    @GetMapping
    public List<AdminModelFallbackGroupResponse> list(
            @Parameter(description = "模型类型：CHAT / IMAGE / VIDEO / AUDIO")
            @RequestParam(required = false) String type,
            @Parameter(description = "只查看指定站点；globalOnly=true 时忽略")
            @RequestParam(required = false) Long siteId,
            @Parameter(description = "只查看全局组")
            @RequestParam(required = false) Boolean globalOnly,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.list(type, siteId, globalOnly);
    }

    @Operation(summary = "版本组详情")
    @GetMapping("/{id}")
    public AdminModelFallbackGroupResponse detail(@PathVariable Long id,
                                                  HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.detail(id);
    }

    @Operation(summary = "创建版本组",
            description = "siteId 留空创建全局组；同 key 的站点组会覆盖全局组。")
    @PostMapping
    public AdminModelFallbackGroupResponse create(
            @Valid @RequestBody AdminModelFallbackGroupRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.create(request);
    }

    @Operation(summary = "完整更新版本组",
            description = "修改站点或类型时，现有成员必须仍满足作用域和类型限制。")
    @PutMapping("/{id}")
    public AdminModelFallbackGroupResponse update(
            @PathVariable Long id,
            @Valid @RequestBody AdminModelFallbackGroupRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.update(id, request);
    }

    @Operation(summary = "删除版本组", description = "只删除组和成员关系，不删除具体模型。")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "加入具体模型",
            description = "允许加入不同系列模型，但模型类型、作用域和上游可用状态必须匹配。")
    @PostMapping("/{id}/members")
    public AdminModelFallbackGroupResponse addMember(
            @PathVariable Long id,
            @Valid @RequestBody AdminModelFallbackMemberRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.addMember(id, request);
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id}/members/{modelId}")
    public AdminModelFallbackGroupResponse removeMember(
            @PathVariable Long id,
            @PathVariable Long modelId,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.removeMember(id, modelId);
    }

    @Operation(summary = "启用或暂停成员")
    @PutMapping("/{id}/members/{modelId}/enabled")
    public AdminModelFallbackGroupResponse setMemberEnabled(
            @PathVariable Long id,
            @PathVariable Long modelId,
            @Valid @RequestBody AdminModelFallbackMemberEnabledRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.setMemberEnabled(id, modelId, request);
    }

    @Operation(summary = "批量启用或暂停成员",
            description = "逐条校验成员资格；不满足条件的成员进入 skipped，不影响其余成员。"
                    + "本来就是目标状态的成员计入 unchanged，不算失败。")
    @PutMapping("/{id}/members/batch-enabled")
    public AdminModelFallbackBatchMemberResponse batchSetMemberEnabled(
            @PathVariable Long id,
            @Valid @RequestBody AdminModelFallbackBatchEnabledRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.batchSetMemberEnabled(id, request);
    }

    @Operation(summary = "批量移除成员",
            description = "一次请求移除多个成员，结束后统一重排 priority。"
                    + "已不是成员的 modelId 计入 skipped，重试安全。")
    @PostMapping("/{id}/members/batch-remove")
    public AdminModelFallbackBatchMemberResponse batchRemoveMembers(
            @PathVariable Long id,
            @Valid @RequestBody AdminModelFallbackBatchRemoveRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.batchRemoveMembers(id, request);
    }

    @Operation(summary = "自定义降级顺序",
            description = "modelIds 必须与当前全部成员一一对应；保存后顺序模式变为 CUSTOM。")
    @PutMapping("/{id}/members/order")
    public AdminModelFallbackGroupResponse reorder(
            @PathVariable Long id,
            @Valid @RequestBody AdminModelFallbackOrderRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.reorder(id, request);
    }

    @Operation(summary = "恢复默认价格顺序",
            description = "恢复为有效价格从低到高，再按模型 sortOrder 和 ID 排序。")
    @PutMapping("/{id}/order/price")
    public AdminModelFallbackGroupResponse usePriceOrder(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.usePriceOrder(id);
    }

    @Operation(summary = "可加入版本组的模型",
            description = "返回 ACTIVE 且上游可用的模型；全局组可选择全局模型，"
                    + "站点组可选择全局模型和本站模型。")
    @GetMapping("/candidates")
    public List<AdminModelFallbackCandidateResponse> candidates(
            @Parameter(description = "模型类型：CHAT / IMAGE / VIDEO / AUDIO")
            @RequestParam(required = false) String type,
            @Parameter(description = "组所属站点；留空查看全局候选")
            @RequestParam(required = false) Long siteId,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return service.candidates(type, siteId);
    }
}
