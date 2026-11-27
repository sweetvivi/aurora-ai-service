package com.hub.backend.modules.document;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Turns extracted text back into a downloadable file.
 *
 * <p>Only the two conversions that need nothing but the text itself are supported: plain text to
 * Word, and plain text to a {@code .txt}. Layout-preserving conversions live in
 * {@code OfficeConverter}, which drives a real renderer.</p>
 */
@Component
public class DocumentExporter {

    /**
     * Font name written into the .docx — a name, not a font file this server needs.
     *
     * <p>A .docx stores the <em>name</em> and the reader resolves it, so this works on a Linux
     * server as long as the client has the face. {@code 微软雅黑} is Word's Windows default;
     * the run also gets a latin family so non-CJK text does not inherit a CJK face.</p>
     */
    private static final String EAST_ASIAN_FONT = "微软雅黑";
    private static final String LATIN_FONT = "Calibri";

    public byte[] toTxt(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a minimal but readable .docx: a centered title, then one paragraph per source line.
     * Styling stays out of the way — the source is plain text, so anything fancier would be invented.
     */
    public byte[] toDocx(String text, String title) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) {
                XWPFParagraph heading = document.createParagraph();
                heading.setAlignment(ParagraphAlignment.CENTER);
                heading.setSpacingAfter(240);
                XWPFRun run = heading.createRun();
                run.setText(title);
                run.setBold(true);
                run.setFontSize(18);
                applyFont(run);
            }

            String body = text == null ? "" : text;
            for (String line : body.split("\n", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setSpacingAfter(120);
                paragraph.setSpacingBetween(1.5);
                XWPFRun run = paragraph.createRun();
                run.setText(line);
                run.setFontSize(11);
                applyFont(run);
            }

            document.write(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("生成 Word 文件失败：" + failure.getMessage(), failure);
        }
    }

    /**
     * Sets both the latin and the east-asian face.
     *
     * <p>{@code XWPFRun.setFontFamily} only writes {@code w:rFonts/@ascii}; Word then uses that
     * same name for CJK too unless {@code w:eastAsia} is set, which is why a run created this way
     * shows the Chinese text in a latin face on a machine without the CJK variant.</p>
     */
    private static void applyFont(XWPFRun run) {
        run.setFontFamily(LATIN_FONT);
        var properties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        var fonts = properties.sizeOfRFontsArray() > 0
                ? properties.getRFontsArray(0)
                : properties.addNewRFonts();
        fonts.setAscii(LATIN_FONT);
        fonts.setHAnsi(LATIN_FONT);
        fonts.setEastAsia(EAST_ASIAN_FONT);
        fonts.setCs(LATIN_FONT);
    }
}
