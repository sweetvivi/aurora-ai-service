package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.file.DocumentTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Turns uploaded bytes into the plain text every tool works from.
 *
 * <p>PDF / DOCX / XLSX / PPTX reuse {@link DocumentTextExtractor}, the same component the chat
 * attachment path already uses, so a file behaves identically in both places. Plain text formats
 * are decoded here: UTF-8 first, GBK as a fallback, because documents exported from older
 * Chinese desktop software are frequently GBK and decoding them as UTF-8 yields mojibake
 * rather than an error.</p>
 */
@Component
public class DocumentTextReader {

    /** Formats the workspace accepts. Legacy .doc/.xls are refused with a hint, see below. */
    public static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("pdf", "docx", "xlsx", "pptx", "txt", "md", "csv");

    /** Refused on purpose: POI without the scratchpad module cannot read them reliably. */
    public static final Set<String> LEGACY_EXTENSIONS = Set.of("doc", "xls", "ppt");

    private static final Charset GBK = Charset.forName("GBK");

    private final DocumentTextExtractor extractor;

    public DocumentTextReader(DocumentTextExtractor extractor) {
        this.extractor = extractor;
    }

    /**
     * @param extension lower case, no dot
     * @param name      original file name, only used in error messages
     * @param bytes     file content
     */
    public Extracted read(String extension, String name, byte[] bytes) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (LEGACY_EXTENSIONS.contains(ext)) {
            throw new InvalidRequestException(
                    "「.%s」是旧版 Office 格式，请另存为 %s 后再上传"
                            .formatted(ext, switch (ext) {
                                case "doc" -> ".docx";
                                case "xls" -> ".xlsx";
                                default -> ".pptx";
                            }));
        }
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new InvalidRequestException("暂不支持解析 .%s 文件".formatted(ext.isBlank() ? "?" : ext));
        }

        String text = switch (ext) {
            case "pdf" -> extractor.extract(ext, name, new ByteArrayInputStream(bytes));
            case "docx", "xlsx", "pptx" -> extractor.extract(ext, name, new ByteArrayInputStream(bytes));
            default -> decodeText(bytes);
        };

        String normalized = normalize(text);
        if (normalized.isBlank()) {
            throw new InvalidRequestException(
                    "「%s」里没有读到可处理的文字，可能是扫描件或纯图片 PDF".formatted(name));
        }
        return new Extracted(normalized, ext.equals("pdf") ? pageCount(bytes) : null);
    }

    /**
     * Keeps paragraph structure but collapses the runs of blank lines PDF extraction leaves behind,
     * so prompts and previews do not waste half their budget on whitespace.
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String decodeText(byte[] bytes) {
        // UTF-8 BOM shows up in files exported from Excel; strip it before decoding.
        int offset = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF
                ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, offset, bytes.length - offset, GBK);
        }
    }

    private static Integer pageCount(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return document.getNumberOfPages();
        } catch (Exception ignored) {
            // Page count is decoration; the text already came out, so do not fail the upload.
            return null;
        }
    }

    /** Extracted body text plus the PDF page count when there is one. */
    public record Extracted(String text, Integer pageCount) {
    }
}
