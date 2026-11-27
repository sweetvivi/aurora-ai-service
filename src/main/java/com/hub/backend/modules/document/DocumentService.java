package com.hub.backend.modules.document;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.document.dto.DocumentResponse;
import com.hub.backend.modules.document.entity.DocumentEntity;
import com.hub.backend.modules.document.repository.DocumentRepository;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Upload, lookup and removal of documents in the 文档处理 workspace.
 *
 * <p>Bytes live in {@code uploaded_files} through {@link FileService}: uploading here is the same
 * operation as attaching a file to a chat, so the extension / MIME allow list, the per-account
 * quota and the checksum all apply without a second implementation. Only after the text has been
 * extracted does a row land in {@code documents}; a parse failure deletes the blob again so a
 * rejected upload never leaves storage behind.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** Matches the limit the page prints; the global attachment limit is higher. */
    public static final long MAX_UPLOAD_BYTES = 30L * 1024 * 1024;

    private static final Map<String, String> FORMAT_BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "PDF"),
            Map.entry("doc", "Word"),
            Map.entry("docx", "Word"),
            Map.entry("xls", "Excel"),
            Map.entry("xlsx", "Excel"),
            Map.entry("csv", "Excel"),
            Map.entry("txt", "TXT"),
            Map.entry("md", "TXT"));

    private final DocumentRepository documents;
    private final DocumentTextReader reader;
    private final FileService fileService;

    public DocumentService(DocumentRepository documents,
                           DocumentTextReader reader,
                           FileService fileService) {
        this.documents = documents;
        this.reader = reader;
        this.fileService = fileService;
    }

    @Transactional
    public DocumentResponse upload(CurrentUser user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("文件内容为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new PayloadTooLargeException("单个文档不能超过 30MB");
        }

        String originalName = sanitize(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String format = FORMAT_BY_EXTENSION.get(extension);
        if (format == null) {
            throw new InvalidRequestException("不支持的文档格式：.%s".formatted(extension));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException failure) {
            throw new InvalidRequestException("读取上传文件失败，请重试");
        }

        // Parse before storing: a scanned PDF is rejected without ever touching the quota.
        DocumentTextReader.Extracted extracted = reader.read(extension, originalName, bytes);

        UploadedFileResponse stored = fileService.upload(user, file, null, FileAssetType.CHAT_ATTACHMENT);
        try {
            DocumentEntity entity = documents.save(new DocumentEntity(
                    user.userId(),
                    user.siteId(),
                    stored.id(),
                    originalName,
                    extension,
                    format,
                    stored.sizeBytes(),
                    extracted.pageCount(),
                    extracted.text(),
                    DocumentEntity.SOURCE_UPLOAD));
            return DocumentResponse.from(entity);
        } catch (RuntimeException failure) {
            fileService.delete(stored.id(), user);
            throw failure;
        }
    }

    /**
     * Creates a document from one of the built-in samples, so the page stays explorable before the
     * user has a file at hand. Samples carry no upload row: {@code fileId} is {@code null}.
     */
    @Transactional
    public DocumentResponse createSample(CurrentUser user, String format) {
        DocumentSamples.Sample sample = DocumentSamples.require(format);
        DocumentEntity entity = documents.save(new DocumentEntity(
                user.userId(),
                user.siteId(),
                null,
                sample.fileName(),
                sample.extension(),
                sample.format(),
                sample.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                sample.pageCount(),
                sample.text(),
                DocumentEntity.SOURCE_SAMPLE));
        return DocumentResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(CurrentUser user) {
        return documents.findByUserIdAndStatusOrderByCreatedAtDesc(user.userId(), DocumentEntity.STATUS_READY)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse detail(CurrentUser user, Long id) {
        return DocumentResponse.from(requireOwned(user, id));
    }

    @Transactional
    public void delete(CurrentUser user, Long id) {        DocumentEntity entity = requireOwned(user, id);
        entity.markDeleted();
        if (entity.getFileId() != null) {
            try {
                fileService.delete(entity.getFileId(), user);
            } catch (RuntimeException failure) {
                // Blob cleanup is best effort; the document must disappear from the list either way.
                log.warn("删除文档 {} 的原始文件失败：{}", id, failure.getMessage());
            }
        }
    }

    /**
     * 原始上传文件的字节。示例文档没有底层文件（正文是服务端内置的），返回 {@code null}；
     * 「按原格式导出」需要它才能把结果写回同一个容器。
     */
    @Transactional(readOnly = true)
    public byte[] originalBytes(CurrentUser user, DocumentEntity entity) {
        if (entity.getFileId() == null) {
            return null;
        }
        DownloadedFile downloaded = fileService.download(entity.getFileId(), user);
        try (InputStream input = downloaded.resource().getInputStream()) {
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new InvalidRequestException("无法读取原始文件：%s".formatted(failure.getMessage()));
        }
    }

    @Transactional(readOnly = true)
    public DocumentEntity requireOwned(CurrentUser user, Long id) {        if (id == null) {
            throw new InvalidRequestException("缺少文档 ID");
        }
        return documents.findByIdAndUserIdAndStatus(id, user.userId(), DocumentEntity.STATUS_READY)
                .orElseThrow(() -> new NotFoundException("文档不存在或已被删除"));
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "未命名文档";
        }
        String cleaned = name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).strip();
        return cleaned.length() <= 512 ? cleaned : cleaned.substring(cleaned.length() - 512);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
