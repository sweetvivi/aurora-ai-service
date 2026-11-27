package com.hub.backend.modules.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Supplies a font that can actually draw Chinese text into a PDF.
 *
 * <p>The standard 14 PDF fonts have no CJK glyphs: {@code showText} either throws or writes
 * question marks, so a watermark or a text-to-PDF conversion without an embedded font would be
 * silently wrong. A TrueType font is loaded once and reused; every produced document embeds a
 * subset of it, which keeps the output from growing by the full font size.</p>
 *
 * <p>Only single-file {@code .ttf}/{@code .otf} faces are supported on purpose — collections
 * ({@code .ttc}) need the underlying file handle to stay open while the subset is written, which
 * is a lifetime the request thread does not own. Deployments without any of the listed fonts fall
 * back to Helvetica and transliterate the text instead of failing the whole action.</p>
 */
@Component
public class PdfFontProvider {

    private static final Logger log = LoggerFactory.getLogger(PdfFontProvider.class);

    /**
     * Checked in order; the first readable file wins.
     *
     * <p>The Linux entries matter more than they look: a container that installs LibreOffice still
     * has <b>no CJK font</b>, because {@code libreoffice-core} depends on {@code fonts-opensymbol}
     * only. Without a CJK face every watermark and text→PDF result silently degrades to
     * {@code ????}. The bundled resource and {@link #overridePath} exist so a server can be fixed
     * without rebuilding the image.</p>
     */
    private static final List<String> CANDIDATES = List.of(
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simsun.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/truetype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/arphic/uming.ttf",
            "/usr/share/fonts/truetype/arphic/ukai.ttf",
            "/System/Library/Fonts/Supplemental/Songti.ttc");

    /** Optional explicit font file, so a deployment can point at its own face. */
    private final String overridePath;

    public PdfFontProvider(@Value("${app.document.font-path:}") String overridePath) {
        this.overridePath = overridePath == null ? "" : overridePath.strip();
    }

    /** Loaded once; {@code null} after a failed lookup so the disk is not re-scanned per call. */
    private byte[] fontBytes;
    private boolean resolved;

    /**
     * @return an embeddable CJK font, or {@code null} when this host has none
     */
    public synchronized PDFont load(PDDocument document) {
        byte[] bytes = font();
        if (bytes == null) {
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        }
        try {
            return PDType0Font.load(document, new ByteArrayInputStream(bytes), true);
        } catch (IOException | RuntimeException exception) {
            log.warn("Unable to embed the CJK font, falling back to Helvetica: {}",
                    exception.getMessage());
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        }
    }

    /**
     * Replaces characters the fallback font cannot encode.
     *
     * <p>Only useful together with {@link #load(PDDocument)} returning {@code null}: passing a
     * Chinese string to a Latin font throws, and losing the whole watermark because the server has
     * no Chinese font is a worse outcome than a readable latin-only placeholder.</p>
     */
    public String sanitize(String text, PDFont font) {
        if (font instanceof PDType0Font) {
            return text == null ? "" : text;
        }
        StringBuilder builder = new StringBuilder(text == null ? 0 : text.length());
        for (int i = 0; i < (text == null ? 0 : text.length()); i++) {
            char ch = text.charAt(i);
            builder.append(ch >= 0x20 && ch < 0x7f ? ch : '?');
        }
        return builder.toString();
    }

    private byte[] font() {
        if (!resolved) {
            resolved = true;
            fontBytes = loadOnce();
        }
        return fontBytes;
    }

    private byte[] loadOnce() {
        ClassPathResource bundled = new ClassPathResource("fonts/document-cn.ttf");
        if (bundled.exists()) {
            try (InputStream input = bundled.getInputStream()) {
                log.info("Using bundled PDF font fonts/document-cn.ttf");
                return input.readAllBytes();
            } catch (IOException exception) {
                log.warn("Bundled PDF font is unreadable: {}", exception.getMessage());
            }
        }
        if (!overridePath.isEmpty()) {
            File configured = new File(overridePath);
            if (configured.isFile() && configured.canRead()) {
                try {
                    log.info("Using configured PDF font {}", overridePath);
                    return Files.readAllBytes(configured.toPath());
                } catch (IOException exception) {
                    log.warn("Unable to read configured PDF font {}: {}",
                            overridePath, exception.getMessage());
                }
            } else {
                log.warn("app.document.font-path points at a missing file: {}", overridePath);
            }
        }
        for (String candidate : CANDIDATES) {
            File file = new File(candidate);
            if (!file.isFile() || !file.canRead()) {
                continue;
            }
            if (candidate.endsWith(".ttc")) {
                // A collection cannot be embedded without keeping the handle open; skip it.
                continue;
            }
            try {
                log.info("Using system PDF font {}", candidate);
                return Files.readAllBytes(file.toPath());
            } catch (IOException exception) {
                log.warn("Unable to read PDF font {}: {}", candidate, exception.getMessage());
            }
        }
        log.warn("No CJK font found; watermarks and text-to-PDF will transliterate to ASCII. "
                + "Drop a TrueType face at src/main/resources/fonts/document-cn.ttf to fix this.");
        return null;
    }
}
