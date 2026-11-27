package com.hub.backend.modules.video;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.video.dto.VideoGenerationRequest;
import com.hub.backend.modules.video.dto.VideoTaskResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asynchronous video generation.
 *
 * <p>Unlike the image endpoint this does not answer with the media: a clip takes minutes, so the
 * call queues a job and returns a task id which the client polls. The result, once ready, is an
 * ordinary attachment reachable through {@code /api/v1/files/{id}/content}.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "视频生成", description = "文生视频 / 图生视频，异步任务 + 轮询")
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;
    private final CurrentUserResolver currentUserResolver;

    public VideoGenerationController(VideoGenerationService videoGenerationService,
                                     CurrentUserResolver currentUserResolver) {
        this.videoGenerationService = videoGenerationService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "提交视频生成任务",
            description = """
                    提交一次视频生成，立即返回任务号，不等待出片。

                    请求会先校验模型类型（必须是 `VIDEO`）、参考图与会话的归属，并检查该账号
                    的在途任务数；通过后写入 `async_tasks` 并唤起后台提交，返回 `202`。

                    结果通过 `GET /api/v1/videos/generations/{taskId}` 轮询获取（前端 5 秒一次，
                    最长 25 分钟）。成功的任务返回 `videoUrl`，指向本平台存储的附件，
                    与普通上传附件完全一致。

                    需要为该视频模型配置供应商密钥；未配置时任务会以 `failed` 结束并给出明确原因，
                    而不是假装成功。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "任务已受理"),
            @ApiResponse(responseCode = "400", description = "提示词为空、参数越界，或模型类型不是 VIDEO",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "参考图或会话不存在，或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "该账号在途任务数已达上限",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/videos/generations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VideoTaskResponse generate(@Valid @RequestBody VideoGenerationRequest request,
                                      HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.require(httpRequest);
        return videoGenerationService.submit(currentUser, request);
    }

    @Operation(summary = "查询视频任务",
            description = "返回任务状态、进度与结果地址。任务不存在或不属于当前账号时返回 404，"
                    + "两者不做区分，避免泄露他人任务是否存在。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "任务详情"),
            @ApiResponse(responseCode = "404", description = "任务不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/videos/generations/{taskId}")
    public VideoTaskResponse get(@Parameter(description = "提交时返回的任务号") @PathVariable String taskId,
                                 HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.require(httpRequest);
        return videoGenerationService.get(taskId, currentUser.userId());
    }

    @Operation(summary = "视频任务历史", description = "按创建时间倒序，仅返回当前账号的任务。")
    @GetMapping("/video-tasks")
    public PageResponse<VideoTaskResponse> list(
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，默认 20，最大 100") @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.require(httpRequest);
        return videoGenerationService.list(currentUser.userId(), page, size);
    }

    @Operation(summary = "取消视频任务",
            description = """
                    请求停止一个在途任务。对已完成、已失败或已取消的任务重复调用不会报错，
                    因此停止按钮在错误时机按下也是安全的。
                    """)
    @PostMapping("/videos/generations/{taskId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@Parameter(description = "提交时返回的任务号") @PathVariable String taskId,
                       HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.require(httpRequest);
        videoGenerationService.cancel(taskId, currentUser.userId());
    }

    @Operation(summary = "删除作品",
            description = """
                    把一条已结束的任务从「我的作品」里删掉，并删除对应的生成视频附件（若有）。
                    参考图附件保留，不随任务删除。

                    仍在排队或生成中的任务不能删除，会返回 `409`——先取消再删除。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "404", description = "任务不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "任务仍在运行",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/video-tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "提交时返回的任务号") @PathVariable String taskId,
                       HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.require(httpRequest);
        videoGenerationService.delete(taskId, currentUser);
    }
}
