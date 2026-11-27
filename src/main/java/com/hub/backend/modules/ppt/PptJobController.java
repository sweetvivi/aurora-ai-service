package com.hub.backend.modules.ppt;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.ppt.PptJobService.DownloadedPpt;
import com.hub.backend.modules.ppt.dto.PptDtos.CreateJobRequest;
import com.hub.backend.modules.ppt.dto.PptDtos.CreateJobResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.EstimateRequest;
import com.hub.backend.modules.ppt.dto.PptDtos.EstimateResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.JobDetailResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.JobSummaryResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.OptimizePromptRequest;
import com.hub.backend.modules.ppt.dto.PptDtos.OptimizePromptResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping(value = "/api/v1/ppt", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI PPT", description = "PPT 生成任务、下载与增强接口")
public class PptJobController {

    private final PptJobService jobService;
    private final PptEstimateService estimateService;
    private final CurrentUserResolver currentUserResolver;

    public PptJobController(PptJobService jobService,
                            PptEstimateService estimateService,
                            CurrentUserResolver currentUserResolver) {
        this.jobService = jobService;
        this.estimateService = estimateService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "创建 PPT 生成任务")
    @PostMapping("/jobs")
    public CreateJobResponse create(@Valid @RequestBody CreateJobRequest body, HttpServletRequest request) {
        return jobService.create(currentUser(request), body);
    }

    @Operation(summary = "查询 PPT 任务详情 / 进度")
    @GetMapping("/jobs/{jobId}")
    public JobDetailResponse detail(@PathVariable String jobId, HttpServletRequest request) {
        return jobService.get(currentUser(request), jobId);
    }

    @Operation(summary = "取消进行中的 PPT 生成")
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String jobId, HttpServletRequest request) {
        jobService.cancel(currentUser(request), jobId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "PPT 历史记录列表")
    @GetMapping("/jobs")
    public PageResponse<JobSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return jobService.list(currentUser(request), status, page, size);
    }

    @Operation(summary = "删除一条 PPT 历史")
    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> delete(@PathVariable String jobId, HttpServletRequest request) {
        jobService.delete(currentUser(request), jobId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "下载生成的 PPTX")
    @GetMapping("/jobs/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable String jobId, HttpServletRequest request) {
        DownloadedPpt downloaded = jobService.download(currentUser(request), jobId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(downloaded.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(downloaded.resource());
    }

    @Operation(summary = "优化提示词")
    @PostMapping("/optimize-prompt")
    public OptimizePromptResponse optimize(@Valid @RequestBody OptimizePromptRequest body,
                                           HttpServletRequest request) {
        currentUser(request);
        return jobService.optimizePrompt(body);
    }

    @Operation(summary = "预估积分消耗")
    @PostMapping("/estimate")
    public EstimateResponse estimate(@RequestBody(required = false) EstimateRequest body,
                                     HttpServletRequest request) {
        currentUser(request);
        return estimateService.estimate(body == null ? new EstimateRequest(null, null, null, null, null) : body);
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        return currentUserResolver.requireAuthenticated(request);
    }
}
