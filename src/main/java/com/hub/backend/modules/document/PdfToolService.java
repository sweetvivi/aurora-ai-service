package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The PDF tools that need nothing but PDFBox: merge, split, protect, watermark, render to images,
 * and lay plain text out as a PDF.
 *
 * <p>Every method works on bytes and returns bytes, so the caller decides where the result lives.
 * Nothing here touches the database, an account or a model — that keeps the file handling testable
 * and keeps {@code DocumentActionService} about orchestration.</p>
 *
 * <p>Layout-preserving conversions (PDF → Word, Word → PDF) are still absent: they need an office
 * renderer, and approximating one produces a file that merely looks converted.</p>
 */
@Service
public class PdfToolService {

    private static final Logger log = LoggerFactory.getLogger(PdfToolService.class);

    /** Guards against a 300-page PDF being rasterised into a multi-gigabyte zip. */
    private static final int MAX_IMAGE_PAGES = 60;
    private static final int MIN_DPI = 72;
    private static final int MAX_DPI = 300;
    private static final int MAX_MERGE_SOURCES = 20;
    private static final int MAX_TEXT_CHARS = 400_000;

    /** A4 in points, the same box Word uses by default. */
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 56f;
    private static final float BODY_SIZE = 11f;
    private static final float BODY_LEADING = 18f;
    private static final float TITLE_SIZE = 17f;

    private final PdfFontProvider fonts;

    public PdfToolService(PdfFontProvider fonts) {
        this.fonts = fonts;
    }

    /**
     * A produced file, ready to be stored.
     *
     * @param summary human readable description shown in the result panel
     */
    public record ProducedFile(String fileName, String extension, String mimeType,
                               byte[] bytes, String summary) {
    }

    /* ---------------- merge ---------------- */

    public ProducedFile merge(String baseName, List<byte[]> sources) {
        if (sources == null || sources.size() < 2) {
            throw new InvalidRequestException("合并至少需要两份 PDF");
        }
        if (sources.size() > MAX_MERGE_SOURCES) {
            throw new InvalidRequestException("一次最多合并 %d 份 PDF".formatted(MAX_MERGE_SOURCES));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationStream(out);
        for (byte[] source : sources) {
            if (source == null || source.length == 0) {
                throw new InvalidRequestException("有一份待合并的文件是空的");
            }
            // PDFBox 3 dropped the InputStream overload: sources are handed over as random-access
            // buffers, which also keeps every document seekable while the merger writes.
            merger.addSource(new RandomAccessReadBuffer(source));
        }
        try {
            merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());
        } catch (IOException exception) {
            throw new InvalidRequestException(
                    "合并失败：其中一份 PDF 可能已加密或已损坏（%s）".formatted(exception.getMessage()));
        }

        byte[] merged = out.toByteArray();
        int pages = pageCount(merged);
        String summary = """
                已合并 %d 份 PDF，共 %d 页。

                文件：%s.pdf
                点击右上角「下载」保存。合并按选择顺序拼接，原文件的书签与表单不会保留。"""
                .formatted(sources.size(), pages, baseName);
        return new ProducedFile(baseName + ".pdf", "pdf", "application/pdf", merged, summary);
    }

    /* ---------------- split ---------------- */

    /**
     * @param ranges {@code null} / blank → one file per page; otherwise groups such as
     *               {@code 1-3,5} → two files ({@code 1-3} and {@code 5})
     */
    public ProducedFile split(String baseName, byte[] source, String ranges) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(source)) {
            int total = document.getNumberOfPages();
            if (total <= 1) {
                throw new InvalidRequestException("这份 PDF 只有 1 页，不需要分割");
            }

            Map<String, byte[]> parts = new LinkedHashMap<>();
            if (ranges == null || ranges.isBlank()) {
                for (int page = 1; page <= total; page++) {
                    try (PDDocument single = new PDDocument()) {
                        single.importPage(document.getPage(page - 1));
                        parts.put("%s-p%02d.pdf".formatted(baseName, page), save(single));
                    }
                }
            } else {
                List<PageRange> parsed = PageRange.parse(ranges, total);
                for (PageRange range : parsed) {
                    try (PDDocument part = new PDDocument()) {
                        for (int page = range.from(); page <= range.to(); page++) {
                            part.importPage(document.getPage(page - 1));
                        }
                        parts.put("%s-%s.pdf".formatted(baseName, range.label()), save(part));
                    }
                }
            }

            String summary = """
                    已按 %s 分割，共 %d 个文件。
                    %s
                    打包为 zip，点击右上角「下载」保存后解压。"""
                    .formatted(
                            ranges == null || ranges.isBlank() ? "每页一份" : "页码范围「" + ranges.strip() + "」",
                            parts.size(),
                            parts.keySet().stream().limit(6).map(name -> "· " + name)
                                    .reduce("", (a, b) -> a + b + "\n"));
            return new ProducedFile(baseName + "-分割.zip", "zip", "application/zip",
                    zip(parts), summary);
        } catch (IOException exception) {
            throw new InvalidRequestException(
                    "分割失败：这份 PDF 可能已加密或已损坏（%s）".formatted(exception.getMessage()));
        }
    }

    /** One requested group of pages, inclusive on both ends. */
    private record PageRange(int from, int to) {

        String label() {
            return from == to ? "p" + from : "p%d-%d".formatted(from, to);
        }

        static List<PageRange> parse(String raw, int totalPages) {
            List<PageRange> ranges = new ArrayList<>();
            for (String token : raw.split("[,，;；\\s]+")) {
                if (token.isBlank()) {
                    continue;
                }
                String[] bounds = token.split("[-~－—]");
                int from;
                int to;
                try {
                    from = Integer.parseInt(bounds[0].strip());
                    to = bounds.length > 1 ? Integer.parseInt(bounds[1].strip()) : from;
                } catch (NumberFormatException exception) {
                    throw new InvalidRequestException(
                            "页码范围「%s」看不懂，请写成 1-3,5 这样的形式".formatted(token));
                }
                if (from > to) {
                    int swap = from;
                    from = to;
                    to = swap;
                }
                if (from < 1 || to > totalPages) {
                    throw new InvalidRequestException(
                            "页码范围 %d-%d 超出文档总页数（共 %d 页）".formatted(from, to, totalPages));
                }
                ranges.add(new PageRange(from, to));
            }
            if (ranges.isEmpty()) {
                throw new InvalidRequestException("页码范围为空，请写成 1-3,5 这样的形式");
            }
            return ranges;
        }
    }

    /* ---------------- protect ---------------- */

    public ProducedFile encrypt(String baseName, byte[] source, String password) {
        String secret = password == null ? "" : password.strip();
        if (secret.length() < 4) {
            throw new InvalidRequestException("打开密码至少 4 位");
        }
        // The owner password only controls permissions; it is never handed to the user, so a
        // derived value is enough. Printing and copying stay allowed — locking those down is a
        // different product decision and would surprise anyone who just wanted a password.
        String ownerSecret = "jg-" + Integer.toHexString(secret.hashCode()) + "-owner";

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(source);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (document.isEncrypted()) {
                throw new InvalidRequestException("这份 PDF 已经加密，请先解除密码再设置新密码");
            }
            AccessPermission permission = new AccessPermission();
            permission.setCanPrint(true);
            permission.setCanExtractContent(true);
            permission.setCanExtractForAccessibility(true);
            permission.setCanModify(false);
            permission.setCanModifyAnnotations(false);
            permission.setCanFillInForm(false);
            permission.setCanAssembleDocument(false);

            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerSecret, secret, permission);
            policy.setEncryptionKeyLength(256);
            policy.setPreferAES(true);
            document.protect(policy);
            document.save(out);

            String summary = """
                    已加密：%s.pdf

                    打开密码：%s
                    权限：允许打印与复制，禁止编辑与增删页面
                    加密算法：AES-256

                    密码只在这里显示一次，请立即记下；忘记后无法恢复。"""
                    .formatted(baseName, secret);
            return new ProducedFile(baseName + "-加密.pdf", "pdf", "application/pdf",
                    out.toByteArray(), summary);
        } catch (IOException exception) {
            throw new InvalidRequestException(
                    "加密失败：这份 PDF 可能已损坏（%s）".formatted(exception.getMessage()));
        }
    }

    /* ---------------- watermark ---------------- */

    public ProducedFile addWatermark(String baseName, byte[] source, String text) {
        String label = text == null || text.isBlank() ? "内部资料" : text.strip();
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(source);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = fonts.load(document);
            String drawable = fonts.sanitize(label, font);

            for (PDPage page : document.getPages()) {
                PDRectangle box = page.getCropBox();
                float width = box.getWidth();
                float height = box.getHeight();
                // Shrink the label until it fits the diagonal, so a long phrase does not run off
                // the page. 0.42 leaves room for the rotation.
                float size = 44f;
                float textWidth = textWidth(font, drawable, size);
                float diagonal = (float) Math.hypot(width, height);
                if (textWidth > diagonal * 0.72f) {
                    size = Math.max(12f, size * (diagonal * 0.72f) / textWidth);
                    textWidth = textWidth(font, drawable, size);
                }

                PDExtendedGraphicsState state = new PDExtendedGraphicsState();
                state.setNonStrokingAlphaConstant(0.16f);
                state.setStrokingAlphaConstant(0.16f);

                // Tiled, not a single centred label: one mark per page is easy to crop out, and
                // the page copy claims tiling. The grid is anchored off-page so the pattern covers
                // the corners too, and every other row is shifted half a step so the marks do not
                // line up into visible columns.
                float stepX = textWidth + size * 3.2f;
                float stepY = size * 5.4f;
                try (PDPageContentStream stream = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.setGraphicsStateParameters(state);
                    stream.setNonStrokingColor(new Color(0x1E, 0x3A, 0x8A));

                    int row = 0;
                    for (float y = -stepY; y < height + stepY; y += stepY) {
                        float offset = (row++ % 2 == 0) ? 0f : stepX / 2f;
                        for (float x = -stepX + offset; x < width + stepX; x += stepX) {
                            stream.beginText();
                            stream.setFont(font, size);
                            stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(35), x, y));
                            stream.showText(drawable);
                            stream.endText();
                        }
                    }
                }
            }

            document.save(out);
            String summary = """
                    已添加水印：%s.pdf

                    水印文字：%s
                    样式：45° 斜向平铺，透明度 16%%，覆盖全部 %d 页

                    水印是叠加在页面内容之上的图形层，不改变原文字层；如需去掉，只能用原文件重新生成。"""
                    .formatted(baseName, label, document.getNumberOfPages());
            return new ProducedFile(baseName + "-水印.pdf", "pdf", "application/pdf",
                    out.toByteArray(), summary);
        } catch (IOException exception) {
            throw new InvalidRequestException(
                    "添加水印失败：这份 PDF 可能已加密或已损坏（%s）".formatted(exception.getMessage()));
        }
    }

    /* ---------------- images ---------------- */

    public ProducedFile toImages(String baseName, byte[] source, int requestedDpi) {
        int dpi = Math.min(MAX_DPI, Math.max(MIN_DPI, requestedDpi <= 0 ? 150 : requestedDpi));
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(source)) {
            int total = document.getNumberOfPages();
            int render = Math.min(total, MAX_IMAGE_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);

            Map<String, byte[]> images = new LinkedHashMap<>();
            for (int page = 0; page < render; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ImageIO.write(image, "png", buffer);
                images.put("%s-p%02d.png".formatted(baseName, page + 1), buffer.toByteArray());
            }

            String note = total > render
                    ? "\n注意：文档共 %d 页，超过单次上限，只渲染了前 %d 页。".formatted(total, render)
                    : "";
            String summary = """
                    已转换为 %d 张 PNG 图片（%d DPI）。

                    %s
                    打包为 zip，点击右上角「下载」保存后解压。%s"""
                    .formatted(render, dpi,
                            images.keySet().stream().limit(4).map(name -> "· " + name)
                                    .reduce("", (a, b) -> a + b + "\n"),
                            note);
            return new ProducedFile(baseName + "-图片.zip", "zip", "application/zip",
                    zip(images), summary);
        } catch (IOException exception) {
            throw new InvalidRequestException(
                    "转图片失败：这份 PDF 可能已加密或已损坏（%s）".formatted(exception.getMessage()));
        }
    }

    /* ---------------- text to PDF ---------------- */

    /**
     * Lays plain text out on A4 pages.
     *
     * <p>This is deliberately not a layout-preserving conversion: the input is already plain text,
     * so there is no layout to preserve. What it does guarantee is that the output is a real PDF
     * with an embedded CJK font, selectable text and sane margins.</p>
     */
    public ProducedFile textToPdf(String baseName, String text, String title) {
        String body = text == null ? "" : text;
        if (body.isBlank()) {
            throw new InvalidRequestException("文档正文为空，无法生成 PDF");
        }
        if (body.length() > MAX_TEXT_CHARS) {
            throw new InvalidRequestException(
                    "正文超过 %d 字，请先分段处理".formatted(MAX_TEXT_CHARS));
        }

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = fonts.load(document);
            List<String> lines = wrap(fonts.sanitize(body, font), font, BODY_SIZE,
                    PAGE_WIDTH - MARGIN * 2);

            int pageIndex = 0;
            int lineIndex = 0;
            boolean first = true;
            while (lineIndex < lines.size() || first) {
                first = false;
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDRenderContext context = new PDRenderContext(document, page, font)) {
                    float y = PAGE_HEIGHT - MARGIN;
                    if (pageIndex == 0 && title != null && !title.isBlank()) {
                        context.write(fonts.sanitize(title.strip(), font), TITLE_SIZE, y);
                        y -= TITLE_SIZE * 2.2f;
                    }
                    while (lineIndex < lines.size() && y > MARGIN) {
                        context.write(lines.get(lineIndex++), BODY_SIZE, y);
                        y -= BODY_LEADING;
                    }
                }
                pageIndex++;
            }

            document.save(out);
            String summary = """
                    已生成 PDF：%s.pdf

                    页数：%d · 每页 A4 · 正文 %s pt · 1.6 倍行距
                    字体已嵌入，中文可搜索、可复制。

                    纯文本没有图片与表格结构：这里按原文换行逐段排版，
                    所以源文件里的硬换行会原样保留成段落。从 PDF 提取出来的文本，
                    每行宽度可能比本页窄，属于正常现象。"""
                    .formatted(baseName, pageIndex, (int) BODY_SIZE);
            return new ProducedFile(baseName + ".pdf", "pdf", "application/pdf",
                    out.toByteArray(), summary);
        } catch (IOException exception) {
            throw new InvalidRequestException(
                    "生成 PDF 失败：%s".formatted(exception.getMessage()));
        }
    }

    /** Small helper so the paging loop above stays readable. */
    private static final class PDRenderContext implements AutoCloseable {

        private final PDFont font;
        private final PDPageContentStream stream;

        PDRenderContext(PDDocument document, PDPage page, PDFont font) throws IOException {
            this.font = font;
            this.stream = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true);
        }

        void write(String line, float size, float y) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(line);
            stream.endText();
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /* ---------------- helpers ---------------- */

    /**
     * Breaks text into lines that fit the given width.
     *
     * <p>Chinese has no spaces, so a word-wise wrapper would emit one line per paragraph. The rule
     * here is: latin runs stay together, CJK characters may break anywhere. Wide characters that
     * alone exceed the width are still emitted, otherwise the loop would never advance.</p>
     */
    private List<String> wrap(String text, PDFont font, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : mergeBrokenLines(text)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            float width = 0;
            for (int i = 0; i < paragraph.length(); ) {
                int codePoint = paragraph.codePointAt(i);
                String chunk = new String(Character.toChars(codePoint));
                // Keep a latin word in one piece: measure up to the next space.
                if (isLatin(codePoint)) {
                    int end = i;
                    while (end < paragraph.length()) {
                        int next = paragraph.codePointAt(end);
                        if (!isLatin(next)) {
                            break;
                        }
                        end += Character.charCount(next);
                    }
                    chunk = paragraph.substring(i, end);
                    i = end;
                } else {
                    i += Character.charCount(codePoint);
                }
                float chunkWidth = textWidth(font, chunk, size);
                // Hanging punctuation: a full-width comma or full stop must not start a line, so
                // when the line is already at the limit the mark hangs past the margin instead of
                // being pushed down. This is what Chinese typesetting does, and it also keeps the
                // left edge straight.
                if (width + chunkWidth > maxWidth && line.length() > 0 && !isClosingMark(chunk)) {
                    lines.add(line.toString());
                    line.setLength(0);
                    width = 0;
                }
                line.append(chunk);
                width += chunkWidth;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    /**
     * Re-joins lines that were broken mid-sentence.
     *
     * <p>Text extracted from a PDF keeps the original hard line breaks, and those breaks land
     * wherever the previous layout happened to end — frequently right before a comma. Feeding them
     * to the wrapper as separate paragraphs reproduces the same ragged result, with punctuation
     * stranded at the start of lines. A line that begins with closing punctuation is therefore
     * appended to the previous one; everything else keeps its break, because in a real .txt a
     * newline usually does mean a new paragraph.</p>
     */
    private List<String> mergeBrokenLines(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (!line.isEmpty() && !paragraphs.isEmpty() && isClosingMark(line.substring(0, 1))) {
                int last = paragraphs.size() - 1;
                if (!paragraphs.get(last).isEmpty()) {
                    paragraphs.set(last, paragraphs.get(last) + line);
                    continue;
                }
            }
            paragraphs.add(line);
        }
        return paragraphs;
    }

    private static boolean isLatin(int codePoint) {
        return codePoint < 0x2e80 && !Character.isWhitespace(codePoint);
    }

    /** Punctuation that Chinese typesetting never allows at the start of a line. */
    private static boolean isClosingMark(String chunk) {
        return chunk.length() == 1 && "，。、；：？！）】》」』”’,.;:?!)]}%".indexOf(chunk.charAt(0)) >= 0;
    }

    private float textWidth(PDFont font, String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException exception) {
            // A glyph the font cannot encode; fall back to a per-character estimate so the layout
            // code never fails on a single odd character.
            return text.length() * size * 0.6f;
        }
    }

    private byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        } catch (IOException exception) {
            throw new InvalidRequestException("打包失败：%s".formatted(exception.getMessage()));
        }
        return out.toByteArray();
    }

    private int pageCount(byte[] pdf) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException exception) {
            log.debug("Unable to count merged pages: {}", exception.getMessage());
            return 0;
        }
    }

    private byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    static String normalizeExtension(String extension) {
        return extension == null ? "" : extension.strip().toLowerCase(Locale.ROOT);
    }
}
