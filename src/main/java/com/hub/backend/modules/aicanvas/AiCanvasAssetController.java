package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.ApiError;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.AssetLinkRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.AssetResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasForms.AssetUploadForm;
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
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ai-canvas/assets")
@Tag(name = "AI 画布-素材", description = "画布素材上传、列表、删除与 Range 流")
public class AiCanvasAssetController {

    private final AiCanvasAssetService assetService;
    private final CurrentUserResolver currentUserResolver;

    public AiCanvasAssetController(AiCanvasAssetService assetService,
                                   CurrentUserResolver currentUserResolver) {
        this.assetService = assetService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "素材列表")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AssetResponse> list(
            @Parameter(description = "character / scene / object / style / sound / other")
            @RequestParam(required = false) String category,
            HttpServletRequest request) {
        return assetService.list(currentUser(request), category);
    }

    @Operation(summary = "上传 AI 画布素材",
            description = "支持图片、视频和音频，单文件上限 200MB，并校验用户总存储配额。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "分类、扩展名或 MIME 类型不支持",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "单文件或用户配额超限",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AssetResponse upload(@Valid @ModelAttribute AssetUploadForm form,
                                HttpServletRequest request) {
        return assetService.upload(
                currentUser(request),
                form.getFile(),
                form.getCategory(),
                form.getName(),
                form.getPrompt(),
                form.getModel());
    }

    @Operation(summary = "把已有附件加入素材库",
            description = """
                    只存引用，不复制字节：素材库里的这条记录指向 `uploaded_files` 的某一行，
                    读取内容时按附件归属校验后转发。

                    同一附件重复调用返回已有素材（幂等）。引用素材不占画布配额 —— 字节已经
                    算在附件配额里，重复计一次等于让用户为同一张图付两份空间。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "加入成功，或该附件已在素材库"),
            @ApiResponse(responseCode = "400", description = "fileId / category 不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "附件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(value = "/link", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AssetResponse link(@Valid @RequestBody AssetLinkRequest body,
                              HttpServletRequest request) {
        return assetService.link(
                currentUser(request),
                body.fileId(),
                body.category(),
                body.name(),
                body.prompt(),
                body.model());
    }

    @Operation(summary = "删除素材")
    @DeleteMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> delete(@PathVariable String key, HttpServletRequest request) {
        assetService.delete(currentUser(request), key);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "读取素材内容，支持 HTTP Range")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "完整素材流",
                    content = @Content(mediaType = "image/*", schema = @Schema(type = "string",
                            format = "binary"))),
            @ApiResponse(responseCode = "206", description = "素材局部流",
                    content = @Content(mediaType = "image/*", schema = @Schema(type = "string",
                            format = "binary"))),
            @ApiResponse(responseCode = "404", description = "素材不存在或不属于当前用户",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{key}/content")
    public ResponseEntity<Resource> content(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request) throws IOException {
        AiCanvasAssetService.DownloadedAsset downloaded =
                assetService.download(currentUser(request), key);
        Resource resource = downloaded.resource();
        long contentLength = resource.contentLength();
        MediaType mediaType = MediaType.parseMediaType(downloaded.asset().getMimeType());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(downloaded.asset().getName(), StandardCharsets.UTF_8)
                .build();

        if (rangeHeader == null || rangeHeader.isBlank()) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(contentLength)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            applyAssetSecurityHeaders(builder, mediaType);
            return builder.body(resource);
        }

        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.size() != 1) {
                throw new IllegalArgumentException("只支持单段 Range 请求");
            }
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long count = end - start + 1;
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes %d-%d/%d".formatted(start, end, contentLength))
                    .contentLength(count)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            applyAssetSecurityHeaders(builder, mediaType);
            return builder.body(new RangedResource(resource, start, count));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }
    }

    /**
     * 素材内容是用户上传的字节，统一禁止浏览器嗅探 MIME；对可执行脚本的矢量图
     * （SVG）再叠加一层 CSP 沙箱，避免直接打开内容地址时执行内嵌脚本。
     */
    private static void applyAssetSecurityHeaders(ResponseEntity.BodyBuilder builder,
                                                  MediaType mediaType) {
        builder.header("X-Content-Type-Options", "nosniff");
        String type = mediaType.getType() + "/" + mediaType.getSubtype();
        if ("image/svg+xml".equalsIgnoreCase(type)) {
            builder.header("Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        return currentUserResolver.requireAuthenticated(request);
    }

    private static final class RangedResource extends AbstractResource {

        private final Resource delegate;
        private final long position;
        private final long count;

        private RangedResource(Resource delegate, long position, long count) {
            this.delegate = delegate;
            this.position = position;
            this.count = count;
        }

        @Override
        public String getDescription() {
            return "Range %d-%d of %s".formatted(position, position + count - 1, delegate);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream input = delegate.getInputStream();
            try {
                input.skipNBytes(position);
                return new BoundedInputStream(input, count);
            } catch (IOException | RuntimeException exception) {
                input.close();
                throw exception;
            }
        }

        @Override
        public long contentLength() {
            return count;
        }

        @Override
        public String getFilename() {
            return delegate.getFilename();
        }

        @Override
        public long lastModified() throws IOException {
            return delegate.lastModified();
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        private BoundedInputStream(InputStream input, long count) {
            super(input);
            this.remaining = count;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
