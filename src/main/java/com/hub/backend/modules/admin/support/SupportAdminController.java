package com.hub.backend.modules.admin.support;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.operations.AdminListResponse;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.support.SupportBotService;
import com.hub.backend.modules.support.SupportConversationAdminService;
import com.hub.backend.modules.support.SupportHandoffAdminService;
import com.hub.backend.modules.support.SupportKnowledgeAdminService;
import com.hub.backend.modules.support.dto.SupportBotConfigResponse;
import com.hub.backend.modules.support.dto.SupportBotConfigUpdateRequest;
import com.hub.backend.modules.support.dto.SupportConversationMessageResponse;
import com.hub.backend.modules.support.dto.SupportConversationSummaryResponse;
import com.hub.backend.modules.support.dto.SupportDocResponse;
import com.hub.backend.modules.support.dto.SupportFaqItemResponse;
import com.hub.backend.modules.support.dto.SupportFaqUpsertRequest;
import com.hub.backend.modules.support.dto.SupportHandoffAdminItem;
import com.hub.backend.modules.support.dto.SupportHandoffAdminListResponse;
import com.hub.backend.modules.support.dto.SupportHandoffStatusUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@AuditLog(target = "SUPPORT")
@RestController
@RequestMapping("/api/admin/v1/support")
@Tag(name = "管理 · 站内客服", description = "客服机器人、知识库、会话与转人工工单")
public class SupportAdminController {

    private final SupportBotService botService;
    private final SupportKnowledgeAdminService knowledgeAdminService;
    private final SupportConversationAdminService conversationAdminService;
    private final SupportHandoffAdminService handoffAdminService;
    private final AdminGuard adminGuard;

    public SupportAdminController(SupportBotService botService,
                                  SupportKnowledgeAdminService knowledgeAdminService,
                                  SupportConversationAdminService conversationAdminService,
                                  SupportHandoffAdminService handoffAdminService,
                                  AdminGuard adminGuard) {
        this.botService = botService;
        this.knowledgeAdminService = knowledgeAdminService;
        this.conversationAdminService = conversationAdminService;
        this.handoffAdminService = handoffAdminService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "客服机器人配置")
    @GetMapping("/config")
    public SupportBotConfigResponse config(@RequestParam(required = false) Long siteId,
                                           HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return botService.adminConfig(resolveSiteId(siteId));
    }

    @Operation(summary = "更新客服机器人配置")
    @PutMapping("/config")
    public SupportBotConfigResponse updateConfig(@RequestParam(required = false) Long siteId,
                                                 @Valid @RequestBody SupportBotConfigUpdateRequest request,
                                                 HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return botService.updateConfig(resolveSiteId(siteId), request);
    }

    @Operation(summary = "FAQ 列表")
    @GetMapping("/faqs")
    public List<SupportFaqItemResponse> faqs(@RequestParam(required = false) Long siteId,
                                             HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return knowledgeAdminService.listFaqs(resolveSiteId(siteId));
    }

    @Operation(summary = "新增 FAQ")
    @PostMapping("/faqs")
    public SupportFaqItemResponse createFaq(@RequestParam(required = false) Long siteId,
                                            @Valid @RequestBody SupportFaqUpsertRequest request,
                                            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return knowledgeAdminService.createFaq(resolveSiteId(siteId), request);
    }

    @Operation(summary = "更新 FAQ")
    @PutMapping("/faqs/{id}")
    public SupportFaqItemResponse updateFaq(@PathVariable Long id,
                                            @Valid @RequestBody SupportFaqUpsertRequest request,
                                            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return knowledgeAdminService.updateFaq(id, request);
    }

    @Operation(summary = "删除 FAQ")
    @DeleteMapping("/faqs/{id}")
    public ResponseEntity<Void> deleteFaq(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        knowledgeAdminService.deleteFaq(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "知识文档列表")
    @GetMapping("/docs")
    public List<SupportDocResponse> docs(@RequestParam(required = false) Long siteId,
                                         HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return knowledgeAdminService.listDocs(resolveSiteId(siteId));
    }

    @Operation(summary = "上传知识文档")
    @PostMapping(value = "/docs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SupportDocResponse uploadDoc(@RequestParam(required = false) Long siteId,
                                        @RequestParam("file") MultipartFile file,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) Boolean enabled,
                                        HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return knowledgeAdminService.uploadDoc(resolveSiteId(siteId), file, title, enabled);
    }

    @Operation(summary = "启用/停用文档")
    @PutMapping("/docs/{id}/enabled")
    public SupportDocResponse setDocEnabled(@PathVariable Long id,
                                            @RequestBody Map<String, Boolean> body,
                                            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        return knowledgeAdminService.setDocEnabled(id, enabled);
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/docs/{id}")
    public ResponseEntity<Void> deleteDoc(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        knowledgeAdminService.deleteDoc(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "客服会话列表（全站用户）")
    @GetMapping("/conversations")
    public AdminListResponse<SupportConversationSummaryResponse> conversations(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return conversationAdminService.list(keyword, page, pageSize);
    }

    @Operation(summary = "客服会话消息")
    @GetMapping("/conversations/{id}/messages")
    public List<SupportConversationMessageResponse> conversationMessages(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return conversationAdminService.messages(id);
    }

    @Operation(summary = "转人工工单列表")
    @GetMapping("/handoffs")
    public SupportHandoffAdminListResponse handoffs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return handoffAdminService.list(status, keyword, page, pageSize);
    }

    @Operation(summary = "转人工工单详情")
    @GetMapping("/handoffs/{id}")
    public SupportHandoffAdminItem handoffDetail(@PathVariable Long id,
                                                 HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return handoffAdminService.detail(id);
    }

    @Operation(summary = "更新转人工工单状态")
    @PutMapping("/handoffs/{id}/status")
    public SupportHandoffAdminItem updateHandoffStatus(
            @PathVariable Long id,
            @Valid @RequestBody SupportHandoffStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return handoffAdminService.updateStatus(id, request.status());
    }

    @Operation(summary = "删除转人工工单")
    @DeleteMapping("/handoffs/{id}")
    public ResponseEntity<Void> deleteHandoff(@PathVariable Long id,
                                              HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        handoffAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private Long resolveSiteId(Long siteId) {
        if (siteId != null) {
            return siteId;
        }
        return botService.requireConfig(null).getSiteId();
    }
}
