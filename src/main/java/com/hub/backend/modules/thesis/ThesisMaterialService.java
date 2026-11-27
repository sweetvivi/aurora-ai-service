package com.hub.backend.modules.thesis;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.DocumentTextExtractor;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the user's uploaded reference material into text the writer can actually read.
 *
 * <p>Before this existed the paper module uploaded attachments to {@code /api/v1/files} purely to
 * hand their ids to the defence-PPT job — the paper itself never saw them. With the PPT step gone,
 * the upload would have become a no-op control, so the extracted text now feeds the chapter body
 * prompts instead.</p>
 *
 * <p>Extraction is delegated to {@link DocumentTextExtractor}, the same one the chat attachments
 * use, so a PDF that works in chat also works here. A file that yields no text (a scanned PDF, for
 * instance) is reported rather than silently ignored: an empty sample makes the model invent
 * content, which is worse than telling the user to upload something readable.</p>
 */
@Service
public class ThesisMaterialService {

    /** Total characters across all attachments; a thesis chapter prompt cannot hold a whole book. */
    private static final int MAX_TOTAL_CHARS = 20_000;

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json");

    private static final Logger log = LoggerFactory.getLogger(ThesisMaterialService.class);

    private final FileService files;
    private final DocumentTextExtractor extractor;

    public ThesisMaterialService(FileService files, DocumentTextExtractor extractor) {
        this.files = files;
        this.extractor = extractor;
    }

    /**
     * Reads and concatenates the given attachments.
     *
     * @return extracted text, empty when there is nothing to read
     * @throws InvalidRequestException when a file is not readable or belongs to someone else
     */
    public String readAll(CurrentUser currentUser, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return "";
        }
        if (currentUser == null) {
            throw new InvalidRequestException("匿名用户不能引用上传的参考资料，请先登录");
        }
        StringBuilder text = new StringBuilder();
        int budget = MAX_TOTAL_CHARS;
        for (Long fileId : fileIds) {
            if (fileId == null) {
                continue;
            }
            DownloadedFile downloaded = files.download(fileId, currentUser);
            UploadedFileEntity row = downloaded.file();
            String extracted = readOne(row, downloaded.resource(), budget);
            if (extracted.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(extracted);
            budget = Math.max(0, budget - extracted.length());
            if (budget == 0) {
                log.debug("论文参考资料达到 {} 字上限，后续附件被截断", MAX_TOTAL_CHARS);
                break;
            }
        }
        return text.toString();
    }

    private String readOne(UploadedFileEntity row, org.springframework.core.io.Resource resource,
                           int budget) {
        String ext = row.getExtension() == null ? "" : row.getExtension().toLowerCase(Locale.ROOT);
        boolean plainText = PLAIN_TEXT_EXTENSIONS.contains(ext) || isPlainTextMime(row.getMimeType());
        boolean office = extractor.supports(ext) || Set.of("doc", "xls", "ppt").contains(ext);
        if (!plainText && !office) {
            throw new InvalidRequestException(
                    "参考资料「%s」暂不支持读取，请上传 txt / md / csv / json / pdf / docx / xlsx / pptx"
                            .formatted(row.getOriginalName()));
        }
        try (InputStream in = resource.getInputStream()) {
            String text = plainText
                    ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : extractor.extract(ext, row.getOriginalName(), in);
            String stripped = text == null ? "" : text.strip();
            if (stripped.isEmpty()) {
                throw new InvalidRequestException(
                        "参考资料「%s」里没有可读取的文字（可能是扫描件或纯图片），请换一份可复制文本的文件"
                                .formatted(row.getOriginalName()));
            }
            int cap = Math.min(MAX_TOTAL_CHARS, budget);
            if (stripped.length() > cap) {
                return stripped.substring(0, cap) + "\n…（内容较长，已截断）";
            }
            return stripped;
        } catch (InvalidRequestException invalid) {
            throw invalid;
        } catch (Exception failure) {
            log.warn("读取参考资材失败 fileId={}: {}", row.getId(), failure.toString());
            throw new InvalidRequestException(
                    "读取参考资料「%s」失败：%s"
                            .formatted(row.getOriginalName(), failure.getMessage()));
        }
    }

    private static boolean isPlainTextMime(String mimeType) {
        return mimeType != null && mimeType.startsWith("text/");
    }
}
