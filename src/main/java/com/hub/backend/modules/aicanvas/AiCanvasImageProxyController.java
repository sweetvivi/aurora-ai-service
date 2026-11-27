package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.aicanvas.AiCanvasImageResolver.LoadedImage;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Same-origin reader for upstream images that are served without CORS headers.
 *
 * <p>The Canvas "调整视角" viewfinder uploads the source image as a WebGL texture, which
 * the browser refuses for cross-origin images unless the response carries
 * {@code Access-Control-Allow-Origin}. Streaming those bytes through the platform keeps the
 * canvas untainted while still requiring a logged-in user.
 */
@RestController
@RequestMapping("/api/v1/ai-canvas")
@Tag(name = "AI 画布-图片代理", description = "把没有 CORS 头的上游图片转成可读取的同源响应")
public class AiCanvasImageProxyController {

    private static final String ROUTE = "/image-proxy";
    private static final Set<String> RASTER_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp",
            "image/gif", "image/bmp", "image/avif", "image/tiff");

    private final AiCanvasImageResolver imageResolver;
    private final AiCanvasPublicUrlGuard publicUrlGuard;
    private final CurrentUserResolver currentUserResolver;

    public AiCanvasImageProxyController(AiCanvasImageResolver imageResolver,
                                        AiCanvasPublicUrlGuard publicUrlGuard,
                                        CurrentUserResolver currentUserResolver) {
        this.imageResolver = imageResolver;
        this.publicUrlGuard = publicUrlGuard;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "代理读取跨域图片",
            description = """
                    需要登录。`url` 必须是绝对的 http(s) 地址，且只能解析到公网地址
                    （私网 / 回环 / 保留地址一律 400）。返回位图原字节和对应的
                    image/* Content-Type，供浏览器放进取景器或 canvas 纹理。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "图片字节流",
                    content = @Content(mediaType = "image/*",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "url 缺失、非 http(s)、指向私网地址或不是位图",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未登录",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping(value = ROUTE, produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> proxy(
            @Parameter(description = "要代理的绝对图片地址", required = true)
            @RequestParam("url") String url,
            HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        URI target = publicUrlGuard.validate(url, "url");
        LoadedImage image = imageResolver.load(target.toString(), currentUser);

        return ResponseEntity.ok()
                .contentType(rasterMediaType(image.mimeType()))
                .contentLength(image.content().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .body(image.content());
    }

    private static MediaType rasterMediaType(String mimeType) {
        String normalized = mimeType == null
                ? ""
                : mimeType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!RASTER_MIME_TYPES.contains(normalized)) {
            throw new InvalidRequestException("该接口只支持位图（PNG / JPEG / WebP）");
        }
        return MediaType.parseMediaType(normalized);
    }
}
