package com.hub.backend.modules.admin.feedback;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.feedback.FeedbackService;
import com.hub.backend.modules.feedback.dto.AdminFeedbackListResponse;
import com.hub.backend.modules.feedback.dto.AdminFeedbackResponse;
import com.hub.backend.modules.feedback.dto.FeedbackStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「意见反馈」列表：查看、改状态、删除。
 *
 * <p>反馈本身没有编辑需求（内容是用户原话，改了就失真），所以这里只有三件事：
 * 列表 / 标记处理状态 / 删掉垃圾与重复提交。
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "FEEDBACK")
@RestController
@RequestMapping("/api/admin/v1/feedback")
@Tag(name = "管理 · 意见反馈", description = "前台反馈页提交内容的查看与处理")
public class FeedbackAdminController {

    private final FeedbackService feedbackService;
    private final AdminGuard adminGuard;

    public FeedbackAdminController(FeedbackService feedbackService, AdminGuard adminGuard) {
        this.feedbackService = feedbackService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "反馈列表",
            description = "status / category 留空表示不筛选；keyword 匹配内容、联系方式、用户名与编号。")
    @GetMapping
    public AdminFeedbackListResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return feedbackService.adminList(status, category, source, keyword, page, pageSize);
    }

    @Operation(summary = "修改处理状态", description = "NEW（待处理）/ HANDLED（已处理）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已更新"),
            @ApiResponse(responseCode = "400", description = "状态值不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "反馈不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}/status")
    public AdminFeedbackResponse updateStatus(@PathVariable Long id,
                                              @Valid @RequestBody FeedbackStatusRequest body,
                                              HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return feedbackService.updateStatus(id, body.status());
    }

    @Operation(summary = "删除反馈")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        feedbackService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
