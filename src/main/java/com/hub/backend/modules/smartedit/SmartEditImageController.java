package com.hub.backend.modules.smartedit;

import com.hub.backend.common.ApiError;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.smartedit.dto.ImageUrlRequest;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import com.hub.backend.modules.smartedit.dto.PhotoModelResponse;
import com.hub.backend.modules.smartedit.dto.RetouchRequest;
import com.hub.backend.modules.smartedit.dto.RetouchResponse;
import com.hub.backend.modules.smartedit.dto.UploadedAssetResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Image tools exposed for the smart-editing workspace.
 */
@RestController
@RequestMapping("/api/v1/smart-editing")
@Tag(name = "智能修图-图片", description = "模型目录、上传、抠图、放大、消除与对话出图")
public class SmartEditImageController {

    private final SmartEditImageService imageService;
    private final CurrentUserResolver currentUserResolver;
    private final ChannelResolver channelResolver;

    public SmartEditImageController(SmartEditImageService imageService,
                                    CurrentUserResolver currentUserResolver,
                                    ChannelResolver channelResolver) {
        this.imageService = imageService;
        this.currentUserResolver = currentUserResolver;
        this.channelResolver = channelResolver;
    }

    @Operation(summary = "修图模型目录")
    @GetMapping("/models")
    public List<PhotoModelResponse> models(HttpServletRequest request) {
        currentUserResolver.requireAuthenticated(request);
        AppSiteEntity site = channelResolver.resolve(request);
        return imageService.models(site.getId());
    }

    @Operation(summary = "上传智能修图图片",
            description = "仅允许 JPG / PNG / WEBP，单文件最大 10MB。`save` 可为 canvas 或 crop。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "文件为空或格式不支持",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "文件超过 10MB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadedAssetResponse upload(
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "canvas 或 crop") @RequestParam(required = false) String save,
            HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.upload(currentUser, file, save);
    }

    @Operation(summary = "一键抠图 v1")
    @PostMapping("/matting")
    public ImageUrlResponse matting(@Valid @RequestBody ImageUrlRequest body,
                                    HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.matting(currentUser, body.imageUrl());
    }

    @Operation(summary = "一键抠图 v2")
    @PostMapping("/matting2")
    public ImageUrlResponse matting2(@Valid @RequestBody ImageUrlRequest body,
                                     HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.matting2(currentUser, body.imageUrl());
    }

    @Operation(summary = "AI 超清放大")
    @PostMapping("/enhance")
    public ImageUrlResponse enhance(@Valid @RequestBody ImageUrlRequest body,
                                    HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.enhance(currentUser, body.imageUrl());
    }

    @Operation(summary = "AI 智能消除", description = "multipart 字段为 image 和 mask。")
    @PostMapping(value = "/removal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageUrlResponse removal(@RequestParam("image") MultipartFile image,
                                    @RequestParam("mask") MultipartFile mask,
                                    HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.removal(currentUser, image, mask);
    }

    @Operation(summary = "多图合成（电商工具）",
            description = "multipart：images 为 1-4 张参考图（重复字段），另有 prompt、可选 model 与 size。")
    @PostMapping(value = "/compose", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageUrlResponse compose(@RequestParam("images") List<MultipartFile> images,
                                    @RequestParam("prompt") String prompt,
                                    @Parameter(description = "图像模型 key，缺省用站点默认")
                                    @RequestParam(required = false) String model,
                                    @RequestParam(required = false) String size,
                                    HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.compose(currentUser, images, prompt, model, size);
    }

    @Operation(summary = "局部重绘/扩图",
            description = "multipart 字段为 image、mask、prompt、可选 model、size（目标画布尺寸）"
                    + "和 outpaint（扩图时直接返回模型生成的完整画布）。")
    @PostMapping(value = "/inpaint", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageUrlResponse inpaint(@RequestParam("image") MultipartFile image,
                                    @RequestParam("mask") MultipartFile mask,
                                    @RequestParam("prompt") String prompt,
                                    @Parameter(description = "图像模型 key，缺省用站点默认")
                                    @RequestParam(required = false) String model,
                                    @Parameter(description = "目标画布尺寸，例如 1360x1024；宽高需为 16 的倍数并与上传图片一致")
                                    @RequestParam(required = false) String size,
                                    @Parameter(description = "true 表示扩图，不执行保护区的本地二次合成")
                                    @RequestParam(required = false, defaultValue = "false") boolean outpaint,
                                    HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.inpaint(currentUser, image, mask, prompt, model, size, outpaint);
    }

    @Operation(summary = "修图对话出图")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "出图成功"),
            @ApiResponse(responseCode = "400", description = "消息或模型参数非法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "上游模型不可用或拒绝请求",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/retouch")
    public RetouchResponse retouch(@Valid @RequestBody RetouchRequest body,
                                   HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return imageService.retouch(currentUser, body);
    }
}
