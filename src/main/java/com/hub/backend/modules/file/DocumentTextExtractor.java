package com.hub.backend.modules.file;

import com.hub.backend.common.exception.InvalidRequestException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/**
 * 把可上传的办公文档抽成纯文本，供对话附件展开进 prompt。
 *
 * <p>只覆盖现代格式（pdf / docx / xlsx / pptx）。老式二进制 .doc/.xls/.ppt
 * 仍可落库，但对话侧会明确提示转换为 OOXML。</p>
 */
@Component
public class DocumentTextExtractor {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "xlsx", "pptx");

    /**
     * @param extension 小写、无点的扩展名
     * @param originalName 用于错误提示
     * @param in 文件流；调用方负责关闭
     * @return 抽取到的文本（可能为空串，例如纯图片 PDF）
     */
    public String extract(String extension, String originalName, InputStream in) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        try {
            return switch (ext) {
                case "pdf" -> extractPdf(in);
                case "docx" -> extractDocx(in);
                case "xlsx" -> extractXlsx(in);
                case "pptx" -> extractPptx(in);
                case "doc", "xls", "ppt" -> throw new InvalidRequestException(
                        "附件 '%s' 是旧版 Office 格式，请另存为 %s 后再上传"
                                .formatted(originalName, modernHint(ext)));
                default -> throw new InvalidRequestException(
                        "附件 '%s'（.%s）暂不支持解析进对话"
                                .formatted(originalName, ext.isBlank() ? "?" : ext));
            };
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception failure) {
            throw new InvalidRequestException(
                    "无法解析附件 '%s'：%s。若文件已加密或为扫描件，请先转成可复制文本"
                            .formatted(originalName,
                                    failure.getMessage() == null ? failure.getClass().getSimpleName()
                                            : failure.getMessage()));
        }
    }

    public boolean supports(String extension) {
        return extension != null
                && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    private static String modernHint(String ext) {
        return switch (ext) {
            case "doc" -> "docx";
            case "xls" -> "xlsx";
            case "ppt" -> "pptx";
            default -> "docx / xlsx / pptx";
        };
    }

    private static String extractPdf(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new InvalidRequestException("PDF 已加密，无法解析进对话");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private static String extractDocx(InputStream in) throws IOException {
        try (XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String extractXlsx(InputStream in) throws IOException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder out = new StringBuilder();
        try (XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            for (Sheet sheet : workbook) {
                if (!out.isEmpty()) {
                    out.append("\n\n");
                }
                out.append("【").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    short last = row.getLastCellNum();
                    for (int i = 0; i < last; i++) {
                        if (i > 0) {
                            line.append('\t');
                        }
                        line.append(formatter.formatCellValue(row.getCell(i)));
                    }
                    String trimmed = line.toString().stripTrailing();
                    if (!trimmed.isBlank()) {
                        out.append(trimmed).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static String extractPptx(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try (XMLSlideShow show = new XMLSlideShow(in)) {
            int index = 0;
            for (XSLFSlide slide : show.getSlides()) {
                index++;
                StringBuilder slideText = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String piece = textShape.getText();
                        if (piece != null && !piece.isBlank()) {
                            if (!slideText.isEmpty()) {
                                slideText.append('\n');
                            }
                            slideText.append(piece.strip());
                        }
                    }
                }
                if (!slideText.isEmpty()) {
                    if (!out.isEmpty()) {
                        out.append("\n\n");
                    }
                    out.append("【第").append(index).append("页】\n").append(slideText);
                }
            }
        }
        return out.toString();
    }
}
