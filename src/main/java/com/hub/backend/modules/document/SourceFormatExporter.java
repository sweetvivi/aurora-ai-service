package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.document.entity.DocumentEntity;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes an AI result back into a file of the <em>source</em> format.
 *
 * <p>The page used to offer only "下载 txt"，whatever the upload was; for a Word or PDF document
 * that is a downgrade the user has to fix by hand. This class keeps the format: text goes back
 * into the original file's own container, and the styling that lives in that container survives.</p>
 *
 * <p>Fidelity differs per format, and deliberately so:</p>
 * <ul>
 *   <li><b>Word</b> — the original {@code .docx} is opened and its paragraphs are rewritten in
 *       place, so run fonts, spacing and paragraph alignment are kept. Tables are left untouched:
 *       matching AI output back onto cells is guesswork, and a half-rewritten table is worse than
 *       an untouched one.</li>
 *   <li><b>PDF</b> — a PDF has no editable structure, so the original is round-tripped through
 *       Word ({@code pdf → docx}, rewrite, {@code docx → pdf}) to keep the page layout close to the
 *       original instead of re-flowing the text on a blank page.</li>
 *   <li><b>Excel</b> — the result is advisory text, not a grid, so it is written as one wrapped
 *       line per row in a fresh sheet.</li>
 *   <li><b>Text</b> — byte for byte.</li>
 * </ul>
 */
@Component
public class SourceFormatExporter {

    private static final Logger log = LoggerFactory.getLogger(SourceFormatExporter.class);

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DocumentExporter exporter;
    private final OfficeConverter converter;
    private final PdfToolService pdfTools;

    public SourceFormatExporter(DocumentExporter exporter,
                                OfficeConverter converter,
                                PdfToolService pdfTools) {
        this.exporter = exporter;
        this.converter = converter;
        this.pdfTools = pdfTools;
    }

    /** One produced file: bytes plus the extension and content type they should be served with. */
    public record Produced(byte[] bytes, String extension, String mimeType) {
    }

    /**
     * @param document the uploaded document being rewritten (its format decides the output)
     * @param original the uploaded bytes, {@code null} for sample documents that have no file
     * @param text     the AI result
     * @param title    heading written into files that are generated from scratch; may be blank
     */
    public Produced rewrite(DocumentEntity document, byte[] original, String text, String title) {
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            throw new InvalidRequestException("这次的结果是空的，没有可保存的内容");
        }

        String extension = document.getExtension() == null
                ? "" : document.getExtension().strip().toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "docx", "doc" -> new Produced(toDocx(original, body, title, extension), "docx", DOCX_MIME);
            case "pdf" -> new Produced(toPdf(document, original, body, title), "pdf", "application/pdf");
            case "xlsx", "xls" -> new Produced(toXlsx(body, title), "xlsx", XLSX_MIME);
            case "" -> new Produced(body.getBytes(StandardCharsets.UTF_8), "txt", "text/plain");
            default -> new Produced(body.getBytes(StandardCharsets.UTF_8), extension, "text/plain");
        };
    }

    // ---------------------------------------------------------------- Word

    /**
     * Rewrites the paragraphs of the original document in place.
     *
     * <p>Blank paragraphs are treated as spacing and left alone; every other paragraph receives
     * one line of the result, in order. Extra lines are appended with the last paragraph's
     * formatting, surplus paragraphs are removed — a translated document usually has the same
     * paragraph count, but "usually" is not a guarantee.</p>
     */
    private byte[] toDocx(byte[] original, String body, String title, String extension) {
        byte[] source = original;
        if ("doc".equals(extension) && source != null) {
            // .doc is a binary format POI cannot read; let the renderer normalise it first.
            source = converter.convert("source.doc", source, "docx", "Word").bytes();
        }
        if (source == null) {
            return exporter.toDocx(body, title);
        }

        List<String> lines = body.lines().toList();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<IBodyElement> elements = document.getBodyElements();
            List<Integer> removeAt = new ArrayList<>();
            XWPFParagraph lastWritten = null;
            int next = 0;

            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof XWPFParagraph paragraph)) {
                    continue; // tables keep their original content
                }
                if (paragraph.getText().isBlank()) {
                    continue; // spacing paragraph, not a line of content
                }
                if (next >= lines.size()) {
                    removeAt.add(i);
                    continue;
                }
                fill(paragraph, lines.get(next++));
                lastWritten = paragraph;
            }

            XWPFParagraph template = lastWritten != null ? lastWritten : lastParagraph(document);
            while (next < lines.size()) {
                XWPFParagraph appended = document.createParagraph();
                XWPFRun run = appended.createRun();
                if (template != null) {
                    copyParagraphStyle(template, appended);
                    copyFirstRunStyle(template, run);
                }
                run.setText(lines.get(next++));
            }

            // Remove from the end so earlier indices stay valid.
            for (int i = removeAt.size() - 1; i >= 0; i--) {
                document.removeBodyElement(removeAt.get(i));
            }

            document.write(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new InvalidRequestException("无法在原文档上写入结果：%s".formatted(failure.getMessage()));
        }
    }

    /** Replaces a paragraph's text while keeping its first run's formatting. */
    private static void fill(XWPFParagraph paragraph, String line) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            paragraph.createRun().setText(line);
            return;
        }
        XWPFRun first = runs.get(0);
        // Clearing every text element first: setText(pos) only overwrites the element at that
        // position, and a run produced by Word often carries several.
        for (int i = first.getCTR().sizeOfTArray() - 1; i >= 0; i--) {
            first.getCTR().removeT(i);
        }
        first.setText(line);
        for (int i = runs.size() - 1; i >= 1; i--) {
            paragraph.removeRun(i);
        }
    }

    private static XWPFParagraph lastParagraph(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        return paragraphs.isEmpty() ? null : paragraphs.get(paragraphs.size() - 1);
    }

    private static void copyParagraphStyle(XWPFParagraph from, XWPFParagraph to) {
        CTPPr source = from.getCTP().getPPr();
        if (source != null) {
            to.getCTP().setPPr((CTPPr) source.copy());
        }
    }

    private static void copyFirstRunStyle(XWPFParagraph from, XWPFRun to) {
        List<XWPFRun> runs = from.getRuns();
        if (runs.isEmpty()) {
            return;
        }
        CTRPr source = runs.get(0).getCTR().getRPr();
        if (source != null) {
            to.getCTR().setRPr((CTRPr) source.copy());
        }
    }

    // ---------------------------------------------------------------- PDF

    /**
     * Keeps the page layout by going through Word: {@code pdf → docx} with the renderer, rewrite
     * the paragraphs, {@code docx → pdf}. When the renderer is missing (or the PDF cannot be
     * imported) the result is rendered from the text instead — same format, re-flowed layout.
     */
    private byte[] toPdf(DocumentEntity document, byte[] original, String body, String title) {
        String base = DocumentActionService.baseName(document.getOriginalName());
        if (original != null && converter.available()) {
            try {
                byte[] asWord = converter.convert(base + ".pdf", original, "docx", "PDF").bytes();
                byte[] rewritten = toDocx(asWord, body, title, "docx");
                return converter.toPdf(base + ".docx", rewritten);
            } catch (RuntimeException failure) {
                log.warn("PDF 原位改写失败，回落为按文本重新排版：{}", failure.getMessage());
            }
        }
        return pdfTools.textToPdf(base, body, base).bytes();
    }

    // ---------------------------------------------------------------- Excel

    private byte[] toXlsx(String body, String title) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("处理结果");
            sheet.setColumnWidth(0, 100 * 256);

            CellStyle wrap = workbook.createCellStyle();
            wrap.setWrapText(true);

            int rowIndex = 0;
            if (title != null && !title.isBlank()) {
                Font bold = workbook.createFont();
                bold.setBold(true);
                CellStyle heading = workbook.createCellStyle();
                heading.setFont(bold);
                Row row = sheet.createRow(rowIndex++);
                Cell cell = row.createCell(0);
                cell.setCellValue(title.strip());
                cell.setCellStyle(heading);
                rowIndex++;
            }

            for (String line : body.lines().toList()) {
                Row row = sheet.createRow(rowIndex++);
                Cell cell = row.createCell(0);
                cell.setCellValue(line);
                cell.setCellStyle(wrap);
            }
            sheet.createFreezePane(0, Math.min(rowIndex, 1));

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new InvalidRequestException("无法生成表格文件：%s".formatted(failure.getMessage()));
        }
    }
}
