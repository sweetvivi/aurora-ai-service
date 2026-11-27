package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.document.dto.DocumentActionRequest;
import com.hub.backend.modules.document.dto.DocumentActionResult;
import com.hub.backend.modules.document.entity.DocumentEntity;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs one tool from the 文档处理 page.
 *
 * <p>Two families live here: text-only actions (return the extracted body, or build a .docx) and
 * model-backed actions (summary, abstract, grammar, translation, polish, mind map, spreadsheet
 * assistance, markdown). Every model call goes through {@link ModelUseResolver} for the upstream
 * key and {@link UserRelayCredentialResolver} for the credentials, the same pair every other
 * generation path in this codebase uses — a page must never be able to pick its own upstream.</p>
 *
 * <p>A third family was added later: byte-level PDF operations (merge, split, protect, watermark,
 * render to images, text → PDF) delegated to {@link PdfToolService}. Those are the only actions
 * that need the original upload rather than the extracted text, so they read the blob through
 * {@link FileService} and store the result back as a generated attachment — the page then
 * downloads it by id. Formats that still need an office renderer (PDF → Word, Word → PDF) remain
 * absent from {@link #SUPPORTED_ACTIONS} on purpose.</p>
 */
@Service
public class DocumentActionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentActionService.class);

    /** Roughly 12k tokens of source text; longer documents are sent as head + tail. */
    private static final int MAX_PROMPT_CHARS = 16000;

    /** The extract tool returns the body verbatim, but a 200k character result helps nobody. */
    private static final int MAX_EXTRACT_CHARS = 60000;

    /** Token budget for one document action: enough for an eight-item review, not a chapter. */
    private static final int DEFAULT_OUTPUT_TOKENS = 3000;

    /** Reasoning models spend this budget before writing the answer, so it has to be reserved. */
    private static final int THINKING_HEADROOM_TOKENS = 2560;

    /** Upper bound when a truncated answer is retried with a larger budget. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("extract", "提取内容");
        LABELS.put("summary", "总结");
        LABELS.put("abstract", "摘要生成");
        LABELS.put("grammar", "语法检查与改写建议");
        LABELS.put("translate", "多语言翻译");
        LABELS.put("polish", "AI 润色 / 改写");
        LABELS.put("mindmap", "思维导图");
        LABELS.put("formula", "公式计算");
        LABELS.put("clean", "数据清洗");
        LABELS.put("format", "格式化数据");
        LABELS.put("markdown", "Markdown 格式化");
        LABELS.put("toWord", "转为 Word");
        LABELS.put("text2word", "转为 Word");
        // Byte-level tools, served by PdfToolService.
        LABELS.put("toImage", "PDF 转图片");
        LABELS.put("merge", "PDF 合并");
        LABELS.put("split", "PDF 分割");
        LABELS.put("encrypt", "PDF 加密");
        LABELS.put("addWatermark", "添加水印");
        LABELS.put("text2pdf", "转为 PDF");
        LABELS.put("encoding", "编码转换");
        // Renderer-backed conversions, served by OfficeConverter.
        LABELS.put("doc2pdf", "Word 转 PDF");
        LABELS.put("doc2image", "Word 转图片");
        LABELS.put("sheet2image", "Excel 转图片");
        LABELS.put("pdf2word", "PDF 转 Word");
    }

    public static final List<String> SUPPORTED_ACTIONS = List.copyOf(LABELS.keySet());

    /** Display label for a tool key; an unknown key falls back to the key itself. */
    public static String labelOf(String action) {
        String key = action == null ? "" : action;
        return LABELS.getOrDefault(key, key);
    }

    /** Actions that consume the stored upload and produce a new file instead of a model answer. */
    private static final Set<String> FILE_ACTIONS = Set.of(
            "toImage", "merge", "split", "encrypt", "addWatermark", "text2pdf", "encoding",
            "doc2pdf", "doc2image", "sheet2image", "pdf2word");

    /** Of those, the ones that only make sense on a PDF. */
    private static final Set<String> PDF_ONLY = Set.of(
            "toImage", "merge", "split", "encrypt", "addWatermark", "pdf2word");

    /** Renderer-backed conversions: the original bytes go through LibreOffice, not our parsers. */
    public static final List<String> RENDERING_ACTIONS =
            List.of("doc2pdf", "doc2image", "sheet2image", "pdf2word");

    private static final Set<String> RENDERED = Set.copyOf(RENDERING_ACTIONS);

    private static final String SYSTEM_PROMPT = """
            你是Aurora AI 的文档处理助手。规则：
            1. 只依据用户提供的文档内容作答，文档里没有的信息要直接说明「文档中没有提到」，不要编造；
            2. 输出纯文本，可以用「一、」「1.」「·」这类序号与短横线组织，但不要输出 Markdown 代码块或 HTML；
            3. 不要复述与提问无关的内容，也不要解释你在做什么，直接给结果；
            4. 使用简体中文（翻译任务除外）。
            """;

    private final LlmClient llmClient;
    private final ModelUseResolver modelResolver;
    private final UserRelayCredentialResolver relayCredentials;
    private final PdfToolService pdfTools;
    private final FileService fileService;
    private final DocumentService documents;
    private final OfficeConverter converter;

    public DocumentActionService(LlmClient llmClient,
                                 ModelUseResolver modelResolver,
                                 UserRelayCredentialResolver relayCredentials,
                                 PdfToolService pdfTools,
                                 FileService fileService,
                                 DocumentService documents,
                                 OfficeConverter converter) {
        this.llmClient = llmClient;
        this.modelResolver = modelResolver;
        this.relayCredentials = relayCredentials;
        this.pdfTools = pdfTools;
        this.fileService = fileService;
        this.documents = documents;
        this.converter = converter;
    }

    public DocumentActionResult run(CurrentUser user, DocumentEntity document, DocumentActionRequest request) {
        String action = request.action() == null ? "" : request.action().strip();
        if (!LABELS.containsKey(action)) {
            throw new InvalidRequestException("「%s」还没有接入后端处理".formatted(
                    action.isBlank() ? "未知操作" : action));
        }
        if (FILE_ACTIONS.contains(action)) {
            return runFileAction(user, document, action, request);
        }
        if ("extract".equals(action)) {
            return extract(document);
        }
        if ("toWord".equals(action) || "text2word".equals(action)) {
            return toWord(document);
        }

        DocumentExcerpt excerpt = DocumentExcerpt.of(document.getContent(), MAX_PROMPT_CHARS);
        String instruction = instruction(action, request);
        String answer = complete(user, document, instruction, excerpt);
        return DocumentActionResult.text(action, LABELS.get(action), answer, excerpt.truncated());
    }

    /**
     * Runs a tool that rewrites the file itself.
     *
     * <p>The produced bytes go into the ordinary attachment store rather than a documents-specific
     * one: quota accounting, the extension allow list and the authenticated download route all
     * already exist there, and a result the user can no longer find after a refresh is worse than
     * no result at all.</p>
     */
    private DocumentActionResult runFileAction(CurrentUser user, DocumentEntity document,
                                               String action, DocumentActionRequest request) {
        String base = baseName(document.getOriginalName());
        if (PDF_ONLY.contains(action)) {
            requirePdf(document, action);
        }
        if (RENDERED.contains(action)) {
            requireRenderable(document, action);
        }

        PdfToolService.ProducedFile produced = switch (action) {
            case "merge" -> pdfTools.merge(mergeName(document, request), mergeSources(user, document, request));
            case "split" -> pdfTools.split(base, originalBytes(user, document),
                    request.option("ranges", ""));
            case "encrypt" -> pdfTools.encrypt(base, originalBytes(user, document),
                    request.option("password", ""));
            case "addWatermark" -> pdfTools.addWatermark(base, originalBytes(user, document),
                    request.option("text", "内部资料"));
            case "toImage" -> pdfTools.toImages(base, originalBytes(user, document),
                    intOption(request, "dpi", 150));
            case "text2pdf" -> pdfTools.textToPdf(base, document.getContent(), base);
            case "encoding" -> transcode(document, request);
            case "doc2pdf" -> rendered(user, document, "pdf", 0, base);
            case "doc2image", "sheet2image" -> renderedImages(user, document,
                    intOption(request, "dpi", 150), base);
            case "pdf2word" -> rendered(user, document, "docx", 0, base);
            default -> throw new InvalidRequestException("未定义的文件动作：" + action);
        };

        UploadedFileResponse stored = fileService.storeGenerated(
                user, produced.bytes(), produced.extension(), produced.mimeType(),
                produced.fileName(), FileAssetType.GENERATED, null, null);
        log.info("文档 {} 执行 {} 生成附件 {}（{} 字节）",
                document.getId(), action, stored.id(), stored.sizeBytes());
        return DocumentActionResult.stored(action, LABELS.get(action), produced.summary(),
                produced.fileName(), produced.extension(), stored.id());
    }

    private static void requirePdf(DocumentEntity document, String action) {
        boolean isPdf = "pdf".equalsIgnoreCase(document.getExtension())
                || "PDF".equalsIgnoreCase(document.getFormat());
        if (!isPdf) {
            throw new InvalidRequestException(
                    "「%s」只支持 PDF，当前文档是 %s"
                            .formatted(LABELS.getOrDefault(action, action), document.getFormat()));
        }
        if (document.getFileId() == null) {
            throw new InvalidRequestException("示例文档没有原始文件，请上传真实 PDF 后再使用这个工具");
        }
    }

    /** Each rendered tool only accepts the source family it was designed for. */
    private static void requireRenderable(DocumentEntity document, String action) {
        String format = document.getFormat() == null ? "" : document.getFormat().toUpperCase(Locale.ROOT);
        String expected = switch (action) {
            case "doc2pdf", "doc2image" -> "Word";
            case "sheet2image" -> "Excel";
            case "pdf2word" -> "PDF";
            default -> "";
        };
        if (!expected.isEmpty() && !expected.equalsIgnoreCase(format)) {
            throw new InvalidRequestException(
                    "「%s」只支持 %s，当前文档是 %s".formatted(LABELS.get(action), expected, format));
        }
        if (document.getFileId() == null) {
            throw new InvalidRequestException("示例文档没有原始文件，请上传真实文件后再使用这个工具");
        }
    }

    /**
     * Runs a conversion through the office renderer.
     *
     * <p>Unlike the text-reflow tools, this one keeps the original layout: LibreOffice reads the
     * real document structure, so fonts, tables and page breaks survive. That is why the source is
     * the stored upload and never {@code document.getContent()}.</p>
     */
    private PdfToolService.ProducedFile rendered(CurrentUser user, DocumentEntity document,
                                                 String target, int dpi, String base) {
        if (!converter.available()) {
            throw new ServiceUnavailableException(
                    "服务端没有部署文档渲染引擎（LibreOffice），格式转换暂不可用");
        }
        OfficeConverter.Converted converted = converter.convert(
                document.getOriginalName(), originalBytes(user, document), target,
                document.getFormat());
        String summary = summaryFor(document, converted, dpi);
        return new PdfToolService.ProducedFile(converted.fileName(), converted.extension(),
                converted.mimeType(), converted.bytes(), summary);
    }

    /**
     * Office document → page images.
     *
     * <p>Two hops because LibreOffice cannot honour a DPI: it exports PNG at whatever its default
     * page resolution is and ignores PixelWidth/PixelHeight. Rendering the intermediate PDF with
     * PDFBox is the only way to make the "150 DPI" the page promises actually true, and it also
     * yields one image per page rather than one per document.</p>
     */
    private PdfToolService.ProducedFile renderedImages(CurrentUser user, DocumentEntity document,
                                                       int dpi, String base) {
        if (!converter.available()) {
            throw new ServiceUnavailableException(
                    "服务端没有部署文档渲染引擎（LibreOffice），格式转换暂不可用");
        }
        int clamped = Math.max(72, Math.min(dpi, 400));
        byte[] pdf = converter.toPdf(document.getOriginalName(), originalBytes(user, document));
        PdfToolService.ProducedFile images = pdfTools.toImages(base, pdf, clamped);

        String summary = """
                已生成：%s

                源文件：%s
                分辨率：%d DPI
                说明：多页文档逐页导出，放在一个 zip 里；版式由渲染引擎还原，
                字体与表格位置与原文档一致。"""
                .formatted(images.fileName(), document.getOriginalName(), clamped);
        return new PdfToolService.ProducedFile(images.fileName(), images.extension(),
                images.mimeType(), images.bytes(), summary);
    }

    private static String summaryFor(DocumentEntity document, OfficeConverter.Converted converted,
                                     int dpi) {
        StringBuilder text = new StringBuilder();
        text.append("已生成：").append(converted.fileName()).append("\n\n");
        text.append("源文件：").append(document.getOriginalName()).append("\n");
        text.append("输出格式：").append(converted.extension().toUpperCase(Locale.ROOT)).append("\n");
        if (converted.pageCount() > 1) {
            text.append("页数：").append(converted.pageCount()).append(" 页\n");
        }
        if (dpi > 0 && !"docx".equals(converted.extension())) {
            text.append("分辨率：").append(Math.max(72, Math.min(dpi, 400))).append(" DPI\n");
        }
        text.append("\n版式说明：本工具由服务端渲染引擎转换，保留原文档的字体、段落与分页版式，")
                .append("与在 Office 里「另存为 / 导出为」的效果一致。");
        if ("docx".equals(converted.extension())) {
            text.append("\n\n注意：PDF 本身不保存段落结构，转出的 Word 以文本与分页为主，")
                    .append("复杂排版（多栏、浮动图形）可能与原稿有差异。");
        }
        return text.toString();
    }

    /** The original upload, not the extracted text — merging and watermarking need the structure. */
    private byte[] originalBytes(CurrentUser user, DocumentEntity document) {
        if (document.getFileId() == null) {
            throw new InvalidRequestException("示例文档没有原始文件，请上传真实文件后再使用这个工具");
        }
        DownloadedFile downloaded = fileService.download(document.getFileId(), user);
        try (InputStream input = downloaded.resource().getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new InvalidRequestException("无法读取原始文件：%s".formatted(exception.getMessage()));
        }
    }

    /**
     * Merge order is the clicked document first, then the ids the page sent, in the order given.
     * Duplicates are dropped: picking the same PDF twice is almost always a mis-click.
     */
    private List<byte[]> mergeSources(CurrentUser user, DocumentEntity document,
                                      DocumentActionRequest request) {
        List<byte[]> sources = new ArrayList<>();
        sources.add(originalBytes(user, document));

        Set<Long> seen = new LinkedHashSet<>();
        seen.add(document.getId());
        for (String token : request.option("documentIds", "").split("[,，\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(token.strip());
            } catch (NumberFormatException exception) {
                throw new InvalidRequestException("待合并的文档 id 不合法：" + token);
            }
            if (!seen.add(id)) {
                continue;
            }
            DocumentEntity other = documents.requireOwned(user, id);
            requirePdf(other, "merge");
            sources.add(originalBytes(user, other));
        }
        return sources;
    }

    /** {@code 报告 + 2 份.pdf}, kept short enough for a file name. */
    private static String mergeName(DocumentEntity document, DocumentActionRequest request) {
        String base = baseName(document.getOriginalName());
        int extra = request.option("documentIds", "").isBlank()
                ? 0
                : request.option("documentIds", "").split("[,，\\s]+").length;
        String name = extra > 0 ? "%s+%d份".formatted(base, extra) : base + "-合并";
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    /**
     * Re-encodes the extracted text.
     *
     * <p>The source is already decoded (UTF-8 with a GBK fallback), so this converts the code
     * page of the text we hold rather than guessing at the original bytes twice.</p>
     */
    private PdfToolService.ProducedFile transcode(DocumentEntity document, DocumentActionRequest request) {
        String target = request.option("target", "GBK").toUpperCase(Locale.ROOT);
        Charset charset;
        String label;
        switch (target) {
            case "UTF-8", "UTF8" -> {
                charset = StandardCharsets.UTF_8;
                label = "UTF-8";
            }
            case "GBK", "GB2312", "GB18030" -> {
                charset = Charset.forName("GBK");
                label = "GBK";
            }
            default -> throw new InvalidRequestException(
                    "不支持的编码「%s」，可选 UTF-8 或 GBK".formatted(target));
        }

        String content = document.getContent() == null ? "" : document.getContent();
        if (content.isBlank()) {
            throw new InvalidRequestException("文档正文为空，无法转换编码");
        }
        String base = baseName(document.getOriginalName());
        byte[] bytes = content.getBytes(charset);
        String summary = """
                已转换为 %s 编码的文本文件：%s-%s.txt

                原文字符数：%s
                输出字节数：%s
                说明：源文件读取时已自动识别 UTF-8 / GBK，这里只改写出时的代码页，
                内容本身不变。用记事本另存为时选对应编码即可得到相同结果。"""
                .formatted(label, base, label, String.format("%,d", content.length()),
                        String.format("%,d", bytes.length));
        return new PdfToolService.ProducedFile(base + "-" + label + ".txt", "txt",
                "text/plain", bytes, summary);
    }

    private static int intOption(DocumentActionRequest request, String key, int fallback) {
        String raw = request.option(key, "");
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException("参数「%s」需要是数字".formatted(key));
        }
    }

    /** Full body plus the numbers the page shows in the result header. */
    private DocumentActionResult extract(DocumentEntity document) {
        String content = document.getContent() == null ? "" : document.getContent();
        boolean truncated = content.length() > MAX_EXTRACT_CHARS;
        String body = truncated ? content.substring(0, MAX_EXTRACT_CHARS) : content;

        StringBuilder text = new StringBuilder();
        text.append("文档：").append(document.getOriginalName()).append('\n');
        text.append("格式：").append(document.getFormat());
        if (document.getPageCount() != null) {
            text.append(" · ").append(document.getPageCount()).append(" 页");
        }
        text.append(" · ").append(String.format("%,d", document.getCharCount())).append(" 字\n");
        text.append("─".repeat(28)).append("\n\n");
        text.append(body);
        if (truncated) {
            text.append("\n\n……（正文过长，此处只显示前 ")
                    .append(MAX_EXTRACT_CHARS)
                    .append(" 字，完整内容请用「下载」导出）");
        }
        return DocumentActionResult.text("extract", LABELS.get("extract"), text.toString(), truncated);
    }

    private DocumentActionResult toWord(DocumentEntity document) {
        String base = baseName(document.getOriginalName());
        String text = """
                已生成 Word 文件：%s.docx

                来源：%s
                段落：按原文换行逐段写入
                样式：标题居中加粗、正文 11pt、1.5 倍行距

                点击右上角「下载」即可保存。原文档的图片、表格与页眉页脚不在纯文本里，
                这部分内容无法随转换保留。""".formatted(base, document.getOriginalName());
        return DocumentActionResult.file("toWord", LABELS.get("toWord"), text,
                base + ".docx", "docx", false);
    }

    private String instruction(String action, DocumentActionRequest request) {
        return switch (action) {
            case "summary" -> switch (request.option("style", "quick")) {
                case "deep" -> "对文档做深度总结：先给一句话结论，再按内容分 3-5 个层次展开，每层 2-3 句，"
                        + "最后列出文档中的关键数字与结论。总长 600 字以内。";
                case "business" -> "以面向管理层的口吻总结：只保留决策相关信息（目标、投入、进度、风险、待拍板事项），"
                        + "条目化，300 字以内。";
                case "academic" -> "以学术评审的口吻总结：研究问题、方法、数据、结论、局限，各一段，"
                        + "指出证据是否充分，500 字以内。";
                case "tech" -> "以技术评审的口吻总结：系统组成、关键实现、依赖与限制、可优化点，"
                        + "条目化，500 字以内。";
                default -> "用一句话结论 + 三个要点总结这份文档，总长 200 字以内。";
            };
            case "abstract" -> switch (request.option("style", "standard")) {
                case "brief" -> "生成一段 100 字以内的简介，说明这份文档讲什么、给谁看。";
                case "concise" -> "生成一段 150 字以内的简明摘要，只保留主干信息。";
                case "bullets" -> "生成 5 条要点式摘要，每条一行，不超过 30 字。";
                default -> "生成一段 250 字以内的标准摘要，结尾另起一行给出 3-5 个关键词，"
                        + "格式为「关键词：A；B；C」。";
            };
            case "grammar" -> "检查文档中的语法、错别字、标点误用、术语不一致与过长句。"
                    + "按「问题 → 位置（引用原句）→ 建议改法」逐条列出，最多 8 条；"
                    + "没有问题就说明未发现问题，不要硬凑。";
            // 占位符在第一个字面量里，必须把拼接整体括起来再 formatted，
            // 否则 formatted 只作用于最后一个字面量，%s 会原样发给模型。
            case "translate" -> ("把文档翻译成%s。保留原有段落与序号结构，专有名词首次出现时在括号里保留原文，"
                    + "不要添加译者注，不要总结，逐段翻译。").formatted(request.option("language", "英文"));
            case "polish" -> ("按以下要求改写文档：%s。保留事实、数字与结构，不要新增内容；"
                    + "改写幅度较大时，在结尾列出 2-3 处典型的前后对照。")
                    .formatted(request.option("instruction", "语言更简洁，去掉冗余修饰"));
            case "mindmap" -> "把文档结构整理成 Markdown 层级大纲：一级标题用「# 文档主题」，"
                    + "下面用「-」逐层展开到三层，节点保持短语化（不超过 15 字），"
                    + "只保留真实存在的章节与要点。";
            case "formula" -> "这是一份表格文本（制表符分隔）。指出每列的含义与数据类型，"
                    + "给出适合的 Excel 公式（求和、平均、条件求和、同比等），写成可直接粘贴的形式，"
                    + "并说明公式放在哪一列。";
            case "clean" -> "这是一份表格文本。找出重复行、空值、格式不一致、疑似异常值，"
                    + "按「问题 → 影响行数 → 处理建议」列出；数据量较大时给出可执行的清洗步骤而不是逐行罗列。";
            case "format" -> "这是一份表格文本。给出格式化方案：数字精度与千分位、金额与百分比、日期格式、"
                    + "表头与冻结窗格建议，并说明哪些列需要特殊处理。";
            case "markdown" -> "把这份纯文本整理成 Markdown：识别标题层级、列表、表格与代码块，"
                    + "输出可直接使用的 Markdown 源码，不要加解释。";
            default -> throw new InvalidRequestException("未定义的动作：" + action);
        };
    }

    private String complete(CurrentUser user, DocumentEntity document, String instruction, DocumentExcerpt excerpt) {
        ModelUseResolver.Resolved model = modelResolver.resolveOrDefault(
                document.getSiteId(), null, LlmModelEntity.TYPE_CHAT);
        ProviderCredentials credentials = relayCredentials.resolve(user, model.id());

        String body = """
                %s

                【文档内容】
                %s""".formatted(instruction, excerpt.text());

        List<LlmChatMessage> messages = List.of(
                LlmChatMessage.system(SYSTEM_PROMPT),
                LlmChatMessage.user(body));

        // 推理模型的思考与正文共用同一个 max_tokens；固定 3000 时，长输出动作（语法检查要列 8 条）
        // 会把预算全花在思考上，正文被截断甚至只剩思考。按模型是否推理预留余量，并在确实被
        // finish_reason=length 截断时抬预算重试一次 —— 同预算重试不会改变结果。
        int budget = model.thinking()
                ? Math.min(MAX_OUTPUT_TOKENS, DEFAULT_OUTPUT_TOKENS + THINKING_HEADROOM_TOKENS)
                : DEFAULT_OUTPUT_TOKENS;
        LlmChatCompletion completion = llmClient.complete(new LlmChatCommand(
                model.upstreamKey(), messages, 0.3, budget, credentials));

        if (isTruncated(completion)) {
            int raised = Math.min(MAX_OUTPUT_TOKENS, budget + THINKING_HEADROOM_TOKENS);
            if (raised > budget) {
                log.info("文档 {} 的动作输出被 {} tokens 截断，提高到 {} 重试",
                        document.getId(), budget, raised);
                completion = llmClient.complete(new LlmChatCommand(
                        model.upstreamKey(), messages, 0.3, raised, credentials));
            }
        }

        String content = completion.contentOrEmpty().strip();
        if (content.isEmpty()) {
            log.warn("文档 {} 的动作返回空内容，finishReason={}", document.getId(), completion.finishReason());
            throw new ServiceUnavailableException("模型这次没有返回内容，请重试");
        }
        return content;
    }

    private static boolean isTruncated(LlmChatCompletion completion) {
        String reason = completion.finishReason();
        return reason != null && "length".equalsIgnoreCase(reason.strip());
    }

    /**
     * Long documents are sent as their head and tail: conclusions usually sit at the end, setup at
     * the beginning, and a hard cut in the middle loses both.
     */
    static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "文档";
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.strip().isEmpty() ? "文档" : base;
    }
}
