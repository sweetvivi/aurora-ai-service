package com.hub.backend.modules.image;

import com.hub.backend.common.ApiError;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.image.dto.ImageGenerationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Image generation.
 *
 * <p>Every request runs through the authenticated account's New API relay token. The balance gate
 * is applied before the image model is called.</p>
 */
@RestController
@RequestMapping("/api/v1/images")
@Tag(name = "图片生成", description = "文生图，结果落入本平台附件存储")
public class ImageGenerationController {

    private final ImageGenerationService imageGenerationService;
    private final CurrentUserResolver currentUserResolver;

    public ImageGenerationController(ImageGenerationService imageGenerationService,
                                     CurrentUserResolver currentUserResolver) {
        this.imageGenerationService = imageGenerationService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "生成图片",
            description = """
                    按提示词生成图片。

                    上游以内联 base64 返回的图片会被存入本平台存储并登记为当前账号的附件，
                    因此返回的 `url` 就是 `/api/v1/files/{id}/content`，与普通上传附件完全一致，
                    之后也可以直接挂到会话里。若上游只返回临时链接，服务端会先尝试下载并落库；
                    只有下载失败时才原样透传且 `owned=false`，避免临时链接过期后历史图片丢失。

                    请求使用当前登录账号绑定的 New API Relay Token 计费。余额不足返回 `402`，
                    New API 或账户计费信息不可用时返回 `503`。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "生成成功"),
            @ApiResponse(responseCode = "400", description = "提示词为空、尺寸或质量档位非法、张数超限",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "conversationId 不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "402", description = "当前账号的 New API 余额不足",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "New API 或账户计费信息不可用",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "上游不可达或拒绝请求",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/generations")
    public ImageGenerationResponse generate(@Valid @RequestBody ImageGenerationRequest request,
                                            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return imageGenerationService.generate(currentUser, request);
    }
}
