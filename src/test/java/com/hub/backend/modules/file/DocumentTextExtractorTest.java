package com.hub.backend.modules.file;

import com.hub.backend.common.exception.InvalidRequestException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsPdfText() throws Exception {
        byte[] pdf = samplePdf("Hello PDF attachment");
        String text = extractor.extract("pdf", "a.pdf", new ByteArrayInputStream(pdf));
        assertTrue(text.contains("Hello PDF attachment"));
    }

    @Test
    void extractsDocxText() throws Exception {
        byte[] docx = sampleDocx("Hello DOCX attachment");
        String text = extractor.extract("docx", "a.docx", new ByteArrayInputStream(docx));
        assertTrue(text.contains("Hello DOCX attachment"));
    }

    @Test
    void extractsXlsxText() throws Exception {
        byte[] xlsx = sampleXlsx("SheetA", "Hello", "XLSX");
        String text = extractor.extract("xlsx", "a.xlsx", new ByteArrayInputStream(xlsx));
        assertTrue(text.contains("SheetA"));
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("XLSX"));
    }

    @Test
    void extractsPptxText() throws Exception {
        byte[] pptx = samplePptx("Hello PPTX attachment");
        String text = extractor.extract("pptx", "a.pptx", new ByteArrayInputStream(pptx));
        assertTrue(text.contains("Hello PPTX attachment"));
    }

    @Test
    void rejectsLegacyOfficeFormats() {
        assertThrows(InvalidRequestException.class,
                () -> extractor.extract("doc", "old.doc",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] samplePdf(String content) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] sampleDocx(String content) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(content);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] sampleXlsx(String sheetName, String a1, String b1) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(a1);
            row.createCell(1).setCellValue(b1);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] samplePptx(String content) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText(content);
            show.write(out);
            return out.toByteArray();
        }
    }
}
