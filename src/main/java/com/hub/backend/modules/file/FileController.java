package com.hub.backend.modules.file;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.dto.ImageWorkResponse;
import com.hub.backend.modules.file.dto.FileUsageRequest;
import com.hub.backend.modules.file.dto.FileUsageResponse;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Attachment upload and retrieval for the current account.
 *
 * <p>Images are ordinary attachments: {@code image/*} types are on the allow list, so a screenshot
 * or photo goes through the same endpoint as a PDF or a spreadsheet.</p>
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "附件与图片", description = "上传附件与图片、查看元数据、下载与删除")
public class FileController {

    private final FileService fileService;
    private final FileUsageService fileUsageService;
    private final CurrentUserResolver currentUserResolver;

    public FileController(FileService fileService,
                          FileUsageService fileUsageService,
                          CurrentUserResolver currentUserResolver) {
        this.fileService = fileService;
        this.fileUsageService = fileUsageService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "上传附件或图片",
            description = """
                    以 `multipart/form-data` 提交，字段名固定为 `file`。可选 `conversationId`
                    把附件挂到某个会话下（会自动校验会话归属）。

                    服务端校验：大小上限、扩展名白名单、MIME 白名单三者同时满足才会落库；
                    返回体带 SHA-256 校验和与下载地址。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "文件为空、扩展名或 MIME 类型不在白名单",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "关联的会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "文件超过大小上限",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadedFileResponse upload(@RequestParam("file") MultipartFile file,
                                       @Parameter(description = "挂载到的会话 ID，可不传")
                                       @RequestParam(required = false) Long conversationId,
                                       HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return fileService.upload(currentUser, file, conversationId);
    }

    @Operation(summary = "附件列表",
            description = """
                    返回当前账号的上传记录，可按会话或来源过滤，按上传时间倒序。

                    `source` 是产出该附件的功能（`image_generation` / `painting` / `smart_edit` /
                    `canvas` / `chat` / `agent`）。`asset_type` 只说明字节用途，多个页面都会写
                    `generated`，所以按页取作品必须用 `source`；V77 之前写入的历史行没有来源，
                    任何 `source` 过滤都不会返回它们。
                    """)
    @GetMapping
    public PageResponse<UploadedFileResponse> list(
            @Parameter(description = "只看某个会话的附件") @RequestParam(required = false) Long conversationId,
            @Parameter(description = "只看某个功能产出的附件") @RequestParam(required = false) String source,
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return fileService.list(currentUser, conversationId, resolveSource(source), page, size);
    }

    @Operation(summary = "共用作品列表",
            description = """
                    当前账号产出的全部图片，按生成时间倒序，不分来源。

                    图片生成、AI 绘画、智能修图、AI 画布四个页面读的是这一份数据：任何一处产出的
                    图在别处也能看到，`source` 只用来标注「谁产出的」。删除走 `DELETE /files/{id}`
                    就是真删，四个页面同时消失；素材库（画布）与智能修图的「移除」不走这个接口。
                    """)
    @GetMapping("/works")
    public PageResponse<ImageWorkResponse> works(
            @Parameter(description = "页面标识；传了就排除该页面已移除的作品（目前只有 smart_edit）")
            @RequestParam(required = false) String scope,
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return fileService.listWorks(currentUser, scope, page, size);
    }

    @Operation(summary = "从本页移除作品（不删除图片）",
            description = "把作品当成素材的页面（智能修图）用它：只让这张图不再出现在该页列表，"
                    + "附件与字节保留，其它页面照常显示。真删除走 DELETE /files/{id}。")
    @PostMapping("/{id}/hide")
    public ResponseEntity<Void> hide(@PathVariable Long id,
                                     @RequestParam String scope,
                                     HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        fileService.hideWork(currentUser, id, scope);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "取消移除（恢复到本页列表）")
    @DeleteMapping("/{id}/hide")
    public ResponseEntity<Void> unhide(@PathVariable Long id,
                                       @RequestParam String scope,
                                       HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        fileService.unhideWork(currentUser, id, scope);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unknown sources are refused rather than ignored: silently returning everything for a typo
     * would look like "the filter works" while showing the whole account.
     */
    private static FileSource resolveSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        FileSource resolved = FileSource.fromValueOrNull(source);
        if (resolved == null) {
            throw new InvalidRequestException("source 取值非法：" + source);
        }
        return resolved;
    }

    @Operation(summary = "附件元数据")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "附件元数据"),
            @ApiResponse(responseCode = "404", description = "附件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public UploadedFileResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return fileService.get(id, currentUser);
    }

    @Operation(summary = "下载附件内容",
            description = "以 `attachment` 方式下发，避免浏览器内联渲染上传内容。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "文件字节流"),
            @ApiResponse(responseCode = "404", description = "附件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        DownloadedFile downloaded = fileService.download(id, currentUser);

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (downloaded.file().getMimeType() != null) {
            mediaType = MediaType.parseMediaType(downloaded.file().getMimeType());
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(downloaded.file().getOriginalName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(downloaded.file().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(downloaded.resource());
    }

    @Operation(summary = "查询附件被哪些页面引用",
            description = """
                    删除是不可逆的，前端在弹确认框之前先问一次这里。

                    返回按页面分组的引用明细（画布素材 / 电商历史 / AI 对话）。没有引用的附件
                    返回 `total = 0`，便于前端按 id 对齐。只统计当前账号自己的附件，别人的
                    附件直接不出现在结果里。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功，按入参顺序返回"),
    })
    @PostMapping(value = "/usages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<FileUsageResponse> usages(@RequestBody FileUsageRequest request,
                                          HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return fileUsageService.describe(currentUser, request.ids());
    }

    @Operation(summary = "删除附件", description = "标记为已删除并清理存储对象。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "404", description = "附件不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        fileService.delete(id, currentUser);
        return ResponseEntity.noContent().build();
    }
}
