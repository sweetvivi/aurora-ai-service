package com.hub.backend.modules.document;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.document.dto.DocumentActionRequest;
import com.hub.backend.modules.document.dto.DocumentActionResult;
import com.hub.backend.modules.document.dto.DocumentCapabilities;
import com.hub.backend.modules.document.dto.DocumentChatRequest;
import com.hub.backend.modules.document.dto.DocumentChatResponse;
import com.hub.backend.modules.document.dto.DocumentExportRequest;
import com.hub.backend.modules.document.dto.DocumentResponse;
import com.hub.backend.modules.document.dto.DocumentSampleRequest;
import com.hub.backend.modules.document.entity.DocumentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Locale;

/**
 * Backend of the 「文档处理」 page ({@code /doc-processing}).
 *
 * <p>The page is a static bundle under {@code /doc-tools/}; it talks to this controller instead of
 * simulating results, so every button on it does real work or is greyed out by
 * {@code GET /capabilities}.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "文档处理", description = "上传文档、执行处理动作、基于文档问答与导出")
public class DocumentController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final DocumentService documents;
    private final DocumentActionService actions;
    private final DocumentChatService chat;
    private final DocumentExporter exporter;
    private final DocumentHistoryService history;
    private final OfficeConverter converter;
    private final SourceFormatExporter sourceExporter;
    private final CurrentUserResolver currentUserResolver;

    public DocumentController(DocumentService documents,
                              DocumentActionService actions,
                              DocumentChatService chat,
                              DocumentExporter exporter,
                              DocumentHistoryService history,
                              OfficeConverter converter,
                              SourceFormatExporter sourceExporter,
                              CurrentUserResolver currentUserResolver) {
        this.documents = documents;
        this.actions = actions;
        this.chat = chat;
        this.exporter = exporter;
        this.history = history;
        this.converter = converter;
        this.sourceExporter = sourceExporter;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "能力清单",
            description = """
                    页面用它决定哪些工具可以点。返回的动作之外的工具一律显示「待上线」，
                    不再由前端硬编码——后端新增一个动作，页面自动可用。
                    """)
    @GetMapping("/capabilities")
    public DocumentCapabilities capabilities() {
        return new DocumentCapabilities(
                DocumentActionService.SUPPORTED_ACTIONS,
                List.of("PDF", "Word", "Excel", "TXT"),
                DocumentService.MAX_UPLOAD_BYTES,
                converter.available(),
                DocumentActionService.RENDERING_ACTIONS);
    }

    @Operation(summary = "上传文档",
            description = """
                    以 `multipart/form-data` 提交，字段名 `file`。上传后立即抽取正文入库，
                    因此返回体已经带字符数与页数；解析失败（扫描件、加密 PDF、旧版 Office 格式）
                    会直接返回 400 并删掉刚存下的文件。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传并解析成功"),
            @ApiResponse(responseCode = "400", description = "格式不支持、文件为空或无法解析",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "超过 30MB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse upload(@RequestParam("file") MultipartFile file,
                                   HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return documents.upload(currentUser, file);
    }

    @Operation(summary = "创建示例文档", description = "页面左侧「示例文件」用，正文由服务端内置，不产生上传文件。")
    @PostMapping(value = "/samples", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DocumentResponse sample(@RequestBody DocumentSampleRequest request,
                                   HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return documents.createSample(currentUser, request.format());
    }

    @Operation(summary = "我的文档列表", description = "按上传时间倒序，只返回当前账号未删除的文档。")
    @GetMapping
    public List<DocumentResponse> list(HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return documents.list(currentUser);
    }

    @Operation(summary = "文档详情")
    @GetMapping("/{id}")
    public DocumentResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return documents.detail(currentUser, id);
    }

    @Operation(summary = "删除文档", description = "软删除索引行，并清理底层上传文件。")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        documents.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "执行一个处理动作",
            description = """
                    动作清单见 `GET /documents/capabilities`。`options` 接受 `style`（总结 / 摘要风格）、
                    `language`（翻译目标语言）、`instruction`（润色要求），其它键忽略。

                    长文档只送开头与结尾给模型，结果里的 `truncated` 会标出来。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "处理完成"),
            @ApiResponse(responseCode = "400", description = "动作未接入或参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "文档不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/actions")
    public DocumentActionResult run(@PathVariable Long id,
                                    @RequestBody DocumentActionRequest request,
                                    HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        DocumentEntity document = documents.requireOwned(currentUser, id);

        long started = System.currentTimeMillis();
        try {
            DocumentActionResult result = actions.run(currentUser, document, request);
            history.recordSuccess(currentUser, document, request, result,
                    System.currentTimeMillis() - started);
            return result;
        } catch (RuntimeException exception) {
            // 失败也留一行：用户看过「处理没有完成」，事后应该能查到是什么动作、什么原因
            history.recordFailure(currentUser, document, request, exception.getMessage(),
                    System.currentTimeMillis() - started);
            throw exception;
        }
    }

    @Operation(summary = "基于文档提问", description = "正文进系统提示，历史轮次跟在后面，只回答文档里有的内容。")
    @PostMapping("/{id}/chat")
    public DocumentChatResponse ask(@PathVariable Long id,
                                    @RequestBody DocumentChatRequest request,
                                    HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        DocumentEntity document = documents.requireOwned(currentUser, id);
        return chat.ask(currentUser, document, request);
    }

    @Operation(summary = "导出为文件",
            description = """
                    目前支持 `format=docx`（正文转 Word）与 `format=txt`（正文纯文本）。
                    直接返回字节流并带 `Content-Disposition`，页面用带令牌的 fetch 拿到后再保存。
                    """)
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam("format") String format,
                                         HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        DocumentEntity document = documents.requireOwned(currentUser, id);
        String base = DocumentActionService.baseName(document.getOriginalName());
        String content = document.getContent() == null ? "" : document.getContent();

        String normalized = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
        byte[] bytes;
        String fileName;
        MediaType mediaType;
        switch (normalized) {
            case "docx" -> {
                bytes = exporter.toDocx(content, base);
                fileName = base + ".docx";
                mediaType = DOCX;
            }
            case "txt" -> {
                bytes = exporter.toTxt(content);
                fileName = base + ".txt";
                mediaType = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
            }
            default -> throw new InvalidRequestException("不支持的导出格式：" + format);
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(bytes);
    }

    @Operation(summary = "按原格式保存处理结果",
            description = """
                    把一次处理动作的结果写回**与源文件同格式**的文件，而不是一律导出 txt：

                    - **Word**：在原始 `.docx` 上逐段改写正文，字体、字号、段落间距与对齐方式沿用原文档；
                      表格不动（把译文重新对回单元格只能靠猜，半改的表格比不改更糟）。
                    - **PDF**：PDF 没有可编辑结构，走「PDF → Word → 改写 → PDF」的往返，
                      尽量保住原来的分页与版式；渲染引擎不可用时退回按文本重新排版。
                    - **Excel**：结果是文字建议而不是表格数据，写成新的工作表，一行一段、自动换行。
                    - **纯文本**：原样输出。

                    结果为字节流，文件名按原文件名加后缀生成（`项目方案书-翻译.pdf`），不覆盖原件。
                    示例文档没有原始文件，直接按文本生成同格式文件。
                    """)
    @PostMapping("/{id}/export")
    public ResponseEntity<byte[]> exportAsSource(@PathVariable Long id,
                                                 @RequestBody DocumentExportRequest request,
                                                 HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        DocumentEntity document = documents.requireOwned(currentUser, id);
        byte[] original = documents.originalBytes(currentUser, document);
        String base = DocumentActionService.baseName(document.getOriginalName());

        SourceFormatExporter.Produced produced = sourceExporter.rewrite(
                document, original, request.text(), base);

        String suffix = cleanSuffix(request.suffix());
        String fileName = (suffix.isEmpty() ? base : base + "-" + suffix) + "." + produced.extension();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(produced.mimeType()))
                .contentLength(produced.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(produced.bytes());
    }

    /** File name suffix: drop path separators and reserved characters, keep it short. */
    private static String cleanSuffix(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.strip()
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "")
                .strip();
        return cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned;
    }
}
