package com.hub.backend.modules.aicanvas;

import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.CoverRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.TitleRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowIdResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowSaveRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.WorkflowSummaryResponse;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/ai-canvas", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI 画布-工作流", description = "画布保存、重命名、封面和公开示例")
public class AiCanvasWorkflowController {

    private final AiCanvasWorkflowService workflowService;
    private final CurrentUserResolver currentUserResolver;

    public AiCanvasWorkflowController(AiCanvasWorkflowService workflowService,
                                      CurrentUserResolver currentUserResolver) {
        this.workflowService = workflowService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "我的 AI 画布列表")
    @GetMapping("/workflows")
    public List<WorkflowSummaryResponse> list(HttpServletRequest request) {
        return workflowService.list(currentUser(request));
    }

    @Operation(summary = "读取 AI 画布")
    @GetMapping("/workflows/{id}")
    public WorkflowResponse get(@PathVariable String id, HttpServletRequest request) {
        return workflowService.get(id, currentUser(request));
    }

    @Operation(summary = "创建或覆盖保存 AI 画布")
    @PostMapping("/workflows")
    public WorkflowIdResponse save(@Valid @RequestBody WorkflowSaveRequest body,
                                   HttpServletRequest request) {
        return workflowService.save(currentUser(request), body);
    }

    @Operation(summary = "重命名 AI 画布")
    @PatchMapping("/workflows/{id}")
    public ResponseEntity<Void> rename(@PathVariable String id,
                                       @Valid @RequestBody TitleRequest body,
                                       HttpServletRequest request) {
        workflowService.rename(id, currentUser(request), body);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "设置 AI 画布封面")
    @PostMapping("/workflows/{id}/cover")
    public ResponseEntity<Void> cover(@PathVariable String id,
                                      @Valid @RequestBody CoverRequest body,
                                      HttpServletRequest request) {
        workflowService.updateCover(id, currentUser(request), body);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "删除 AI 画布")
    @DeleteMapping("/workflows/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        workflowService.delete(id, currentUser(request));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "公开 AI 画布列表")
    @GetMapping("/public-workflows")
    public List<WorkflowSummaryResponse> publicWorkflows() {
        return workflowService.publicWorkflows();
    }

    @Operation(summary = "公开 AI 画布详情")
    @GetMapping("/public-workflows/{id}")
    public WorkflowResponse publicWorkflow(@PathVariable String id) {
        return workflowService.publicWorkflow(id);
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        return currentUserResolver.requireAuthenticated(request);
    }
}
