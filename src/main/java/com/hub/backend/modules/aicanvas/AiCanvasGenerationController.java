package com.hub.backend.modules.aicanvas;

import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.GenerationResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.LocalModelGenerateRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.LocalModelResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.TaskResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.VideoGenerationRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.VideoTaskListResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.VideoTrimRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasForms.ImageGenerationForm;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/ai-canvas", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI 画布-生成", description = "图像、视频、任务和本地模型接口")
public class AiCanvasGenerationController {

    private final AiCanvasGenerationService generationService;
    private final CurrentUserResolver currentUserResolver;

    public AiCanvasGenerationController(AiCanvasGenerationService generationService,
                                        CurrentUserResolver currentUserResolver) {
        this.generationService = generationService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "画布图像生成")
    @PostMapping(value = "/images/generations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GenerationResponse image(
            @Valid @ModelAttribute ImageGenerationForm form,
            HttpServletRequest request) {
        List<MultipartFile> references = new ArrayList<>();
        if (form.getImages() != null) {
            references.addAll(form.getImages());
        }
        if (form.getImage() != null && !form.getImage().isEmpty()) {
            references.add(form.getImage());
        }
        return generationService.generateImage(
                currentUser(request),
                form.getPrompt(),
                form.getImageModel(),
                form.getAspectRatio(),
                form.getResolution(),
                references,
                form.getImageBase64(),
                form.getReferenceUrls(),
                form.getSource());
    }

    @Operation(summary = "画布视频生成",
            description = """
                    首帧 / 尾帧支持 data URL、裸 base64、平台附件地址与画布素材地址；服务端把它们
                    落成附件后转交平台视频模块，立即返回 taskId，结果用 `GET /tasks/{taskId}` 轮询。
                    余额校验、并发上限与计费沿用视频模块，未配置视频模型时返回 404。""")
    @ApiResponse(responseCode = "400", description = "请求参数不合法，或只给了尾帧没给首帧")
    @ApiResponse(responseCode = "402", description = "当前账号的 New API 余额不足")
    @ApiResponse(responseCode = "429", description = "该账号并发生成的视频任务已达上限")
    @PostMapping("/videos/generations")
    public GenerationResponse video(@Valid @RequestBody VideoGenerationRequest body,
                                    HttpServletRequest request) {
        return generationService.generateVideo(currentUser(request), body);
    }

    @Operation(summary = "裁剪画布视频",
            description = "videoDataUrl 支持 base64 视频 Data URL，也支持公网 http(s) 视频地址。")
    @PostMapping("/videos/trim")
    public GenerationResponse trim(@Valid @RequestBody VideoTrimRequest body,
                                   HttpServletRequest request) {
        return generationService.trimVideo(
                currentUser(request), body.videoDataUrl(), body.startTime(), body.endTime());
    }

    @Operation(summary = "查询生成任务")
    @GetMapping("/tasks/{taskId}")
    public TaskResponse task(@PathVariable String taskId, HttpServletRequest request) {
        return generationService.task(currentUser(request), taskId);
    }

    @Operation(summary = "视频生成历史")
    @GetMapping("/video-tasks")
    public VideoTaskListResponse videoTasks(HttpServletRequest request) {
        return generationService.videoTasks(currentUser(request));
    }

    @Operation(summary = "本地模型目录")
    @GetMapping("/local-models")
    public List<LocalModelResponse> localModels(HttpServletRequest request) {
        currentUser(request);
        return generationService.localModels();
    }

    @Operation(summary = "调用本地模型")
    @PostMapping("/local-models/generate")
    public GenerationResponse generateLocal(@Valid @RequestBody LocalModelGenerateRequest body,
                                            HttpServletRequest request) {
        return generationService.generateLocal(currentUser(request), body);
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        return currentUserResolver.requireAuthenticated(request);
    }
}
