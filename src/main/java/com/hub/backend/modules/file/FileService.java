package com.hub.backend.modules.file;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.common.exception.StorageException;
import com.hub.backend.infra.queue.TaskMessage;
import com.hub.backend.infra.queue.TaskQueue;
import com.hub.backend.infra.storage.FileStorage;
import com.hub.backend.infra.storage.StoredObject;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.file.dto.ImageWorkResponse;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.file.entity.HiddenWorkEntity;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.file.event.FileDeletedEvent;
import com.hub.backend.modules.file.repository.HiddenWorkRepository;
import com.hub.backend.modules.file.repository.UploadedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Attachment upload, lookup and removal.
 *
 * <p>Validation is deliberately belt and braces: the size is checked against the configured limit,
 * the extension and declared MIME type must both be allowed, and they must describe the same file
 * family. Recognised image and PDF signatures are also checked against the bytes, because both file
 * names and {@code Content-Type} are client controlled.</p>
 *
 * <p>Blob first, metadata second. If the metadata insert fails the blob is deleted again, so a
 * failed upload never leaves an orphan object behind.</p>
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;

    private static final String FALLBACK_NAME = "attachment";
    private static final int MAX_NAME_LENGTH = 512;
    private static final int SIGNATURE_READ_LENGTH = 16;

    /** Pages allowed to remove a work from their own list without deleting it. */
    private static final Set<String> HIDE_SCOPES = Set.of("smart_edit");

    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] GIF87A_SIGNATURE = ascii("GIF87a");
    private static final byte[] GIF89A_SIGNATURE = ascii("GIF89a");
    private static final byte[] RIFF_SIGNATURE = ascii("RIFF");
    private static final byte[] WEBP_SIGNATURE = ascii("WEBP");
    private static final byte[] BMP_SIGNATURE = ascii("BM");
    private static final byte[] PDF_SIGNATURE = ascii("%PDF-");
    private static final byte[] OGG_SIGNATURE = ascii("OggS");
    private static final byte[] WEBM_SIGNATURE = {(byte) 0x1a, (byte) 0x45, (byte) 0xdf, (byte) 0xa3};

    private static final Map<String, Set<String>> EXTENSION_CONTENT_TYPES = Map.ofEntries(
            Map.entry("png", Set.of("image/png")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("bmp", Set.of("image/bmp")),
            Map.entry("mp3", Set.of("audio/mpeg", "audio/mp3")),
            Map.entry("wav", Set.of("audio/wav", "audio/x-wav", "audio/wave")),
            Map.entry("m4a", Set.of("audio/mp4", "audio/x-m4a")),
            Map.entry("ogg", Set.of("audio/ogg", "application/ogg")),
            Map.entry("webm", Set.of("audio/webm", "video/webm")),
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("txt", Set.of("text/plain")),
            Map.entry("md", Set.of("text/markdown", "text/plain")),
            Map.entry("csv", Set.of("text/csv", "text/plain")),
            Map.entry("json", Set.of("application/json")),
            Map.entry("doc", Set.of("application/msword")),
            Map.entry("docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry("xls", Set.of("application/vnd.ms-excel")),
            Map.entry("xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            Map.entry("pptx", Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation")));

    private final UploadedFileRepository repository;
    private final HiddenWorkRepository hiddenWorkRepository;
    private final FileStorage storage;
    private final StorageProperties storageProperties;
    private final ConversationService conversationService;
    private final ApplicationEventPublisher events;
    private final TaskQueue taskQueue;

    public FileService(UploadedFileRepository repository,
                       HiddenWorkRepository hiddenWorkRepository,
                       FileStorage storage,
                       StorageProperties storageProperties,
                       ConversationService conversationService,
                       ApplicationEventPublisher events,
                       TaskQueue taskQueue) {
        this.repository = repository;
        this.hiddenWorkRepository = hiddenWorkRepository;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.conversationService = conversationService;
        this.events = events;
        this.taskQueue = taskQueue;
    }

    /**
     * 单文件上传。
     *
     * <p>这里**故意不开事务**：blob 落盘是磁盘 IO，慢的时候几百毫秒到几秒，而连接池只有 10 条，
     * 事务一旦罩住整段写盘，几个并发上传就能把池占满、把整站拖死。事务边界只留「落库」这一步
     * （{@code repository.save} 自带短事务），写盘期间不占连接。</p>
     */
    public UploadedFileResponse upload(CurrentUser currentUser, MultipartFile file, Long conversationId) {
        return upload(currentUser, file, conversationId, FileAssetType.CHAT_ATTACHMENT);
    }

    /**
     * 同上：校验与配额检查照旧，blob 先落盘（不占连接），落库失败时把 blob 删掉，不留孤儿文件。
     */
    public UploadedFileResponse upload(CurrentUser currentUser, MultipartFile file, Long conversationId,
                                       FileAssetType assetType) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("文件内容为空");
        }

        long limit = storageProperties.getMaxFileSize().toBytes();
        if (file.getSize() > limit) {
            throw new PayloadTooLargeException(
                    "附件不能超过 %d 字节".formatted(limit));
        }

        String originalName = sanitizeName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!storageProperties.isExtensionAllowed(extension)) {
            throw new InvalidRequestException("不支持的文件扩展名：%s".formatted(extension));
        }
        // Windows / 部分浏览器上传 Office 时常给空类型或 application/octet-stream，按扩展名回填。
        String contentType = resolveContentType(extension, file.getContentType());
        if (!storageProperties.isContentTypeAllowed(contentType)) {
            throw new InvalidRequestException(
                    "不支持的文件类型：%s".formatted(file.getContentType()));
        }
        validateFileFamily(extension, contentType, readSignature(file));
        requireStorageQuota(currentUser, file.getSize());
        if (conversationId != null) {
            // throws 404 when the thread is unknown or belongs to another account
            conversationService.requireOwned(conversationId, currentUser.userId());
        }

        StoredObject stored = writeBlob(file, extension);
        try {
            UploadedFileEntity entity = repository.save(new UploadedFileEntity(
                    currentUser.userId(),
                    conversationId,
                    stored.storageKey(),
                    originalName,
                    contentType,
                    extension,
                    assetType,
                    stored.sizeBytes(),
                    stored.checksumSha256()));
            return UploadedFileResponse.from(entity);
        } catch (RuntimeException exception) {
            deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public UploadedFileResponse get(Long fileId, CurrentUser currentUser) {
        return UploadedFileResponse.from(requireOwned(fileId, currentUser));
    }

    /**
     * 生成前的粗检查。
     *
     * <p>出图的字节要等上游返回才有，那时钱已经花了；等到落库才发现空间不足，用户既拿不到图
     * 又扣了额度。所以这里先按「一张图的预估体积」探一次剩余空间。</p>
     */
    @Transactional(readOnly = true)
    public void requireRoomFor(CurrentUser currentUser, long expectedBytes) {
        requireStorageQuota(currentUser, expectedBytes);
    }

    /**
     * 附件总量闸门：单文件上限挡不住反复上传，这里按账号累计。
     *
     * <p>{@code app.storage.max-user-bytes} 为 0 或负数时不生效（测试 / 本地调试）。</p>
     */
    private void requireStorageQuota(CurrentUser currentUser, long incomingBytes) {
        long quota = storageProperties.getMaxUserBytes().toBytes();
        if (quota <= 0 || incomingBytes <= 0) {
            return;
        }
        long used = repository.sumSizeBytesByUserIdAndStatus(
                currentUser.userId(), UploadedFileEntity.STATUS_UPLOADED);
        if (quota - used < incomingBytes) {
            throw new PayloadTooLargeException(
                    "存储空间不足（已用 %s，上限 %s），请先删除一些作品后重试"
                            .formatted(megabytes(used), megabytes(quota)));
        }
    }

    private static String megabytes(long bytes) {
        return "%.0f MB".formatted(bytes / (1024.0 * 1024.0));
    }

    @Transactional(readOnly = true)
    public PageResponse<UploadedFileResponse> list(CurrentUser currentUser, Long conversationId,
                                                   Integer page, Integer size) {
        return list(currentUser, conversationId, null, page, size);
    }

    /**
     * Attachment listing, optionally narrowed to one conversation or to one producing feature.
     *
     * <p>{@code source} is what a per-page work list needs: several features write
     * {@link FileAssetType#GENERATED}, so filtering on the asset type alone would return every
     * picture of the account. Rows written before the column existed have no source and match no
     * filter, which is deliberate — a page must not claim pictures it cannot explain.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<UploadedFileResponse> list(CurrentUser currentUser, Long conversationId,
                                                   FileSource source, Integer page, Integer size) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);

        Page<UploadedFileEntity> result;
        if (conversationId != null) {
            result = repository.findByUserIdAndConversationIdAndStatusOrderByIdDesc(
                    currentUser.userId(), conversationId, UploadedFileEntity.STATUS_UPLOADED, pageable);
        } else if (source != null) {
            result = repository.findByUserIdAndSourceAndStatusOrderByIdDesc(
                    currentUser.userId(), source.value(), UploadedFileEntity.STATUS_UPLOADED, pageable);
        } else {
            result = repository.findByUserIdAndStatusOrderByIdDesc(
                    currentUser.userId(), UploadedFileEntity.STATUS_UPLOADED, pageable);
        }

        return PageResponse.of(
                result.getContent().stream().map(UploadedFileResponse::from).toList(),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    /**
     * The shared work list: every picture this account produced, whichever page produced it.
     *
     * <p>Since V88 all four galleries read this one list, so a picture generated on the
     * image-generation page is visible in AI drawing, the smart-editing workbench and the canvas
     * material library, and deleting it once removes it everywhere. Uploads are excluded by asset
     * type: a reference photo the user attached is not something they made.</p>
     */
    @Transactional(readOnly = true)
    public PageResponse<ImageWorkResponse> listWorks(CurrentUser currentUser, String scope,
                                                     Integer page, Integer size) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);

        List<String> assetTypes =
                List.of(FileAssetType.GENERATED.value(), FileAssetType.EDIT_RESULT.value());
        String resolvedScope = scope == null || scope.isBlank() ? null : requireScope(scope);
        Page<UploadedFileEntity> result = resolvedScope == null
                ? repository.findWorks(
                        currentUser.userId(), assetTypes,
                        UploadedFileEntity.STATUS_UPLOADED, pageable)
                : repository.findWorksExcludingHidden(
                        currentUser.userId(), assetTypes,
                        UploadedFileEntity.STATUS_UPLOADED, resolvedScope, pageable);
        return PageResponse.of(
                result.getContent().stream().map(ImageWorkResponse::from).toList(),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    @Transactional(readOnly = true)
    public DownloadedFile download(Long fileId, CurrentUser currentUser) {        UploadedFileEntity file = requireOwned(fileId, currentUser);
        return new DownloadedFile(file, storage.load(file.getStorageKey()));
    }

    /**
     * Marks the attachment as removed and schedules the blob deletion.
     *
     * <p>删盘本身不在事务体里做：删盘不可回滚，先删字节再回滚事务，行还在而文件已经没了 ——
     * 列表里留着一张打不开的卡片。</p>
     *
     * <p>但也不能挂在 {@code afterCommit} 上：那一步丢了就丢了。进程在提交之后退出、或者存储
     * 短暂不可用，对象会永远留在盘上，没有任何地方记得它该被删。现在改成往队列里投一条消息
     * （与删行同一个事务，回滚则消息不存在），由 {@link StorageCleanupHandler} 重试到成功。</p>
     */
    @Transactional
    public void delete(Long fileId, CurrentUser currentUser) {
        UploadedFileEntity file = requireOwned(fileId, currentUser);
        file.markDeleted();
        taskQueue.publish(TaskMessage.of(StorageCleanupHandler.QUEUE, file.getStorageKey()));
        // 隐藏记录跟着走：附件 ID 是自增的，不会复用，但留着一条指向已删除作品的记录没有意义
        hiddenWorkRepository.deleteByFileId(fileId);
        // 画布素材是引用行，指向已删除的附件就成了打不开的卡片，由监听方自己清理
        events.publishEvent(new FileDeletedEvent(fileId, currentUser.userId()));
    }

    @Transactional(readOnly = true)
    public UploadedFileEntity requireOwned(Long fileId, CurrentUser currentUser) {
        return repository
                .findByIdAndUserIdAndStatus(fileId, currentUser.userId(), UploadedFileEntity.STATUS_UPLOADED)
                .orElseThrow(() -> new NotFoundException("附件 %d 不存在或已删除".formatted(fileId)));
    }

    private StoredObject writeBlob(MultipartFile file, String extension) {
        try (InputStream content = file.getInputStream()) {
            return storage.store(content, extension);
        } catch (IOException exception) {
            throw new StorageException("无法读取上传的附件", exception);
        }
    }

    private static byte[] readSignature(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return content.readNBytes(SIGNATURE_READ_LENGTH);
        } catch (IOException exception) {
            throw new StorageException("无法读取上传的附件", exception);
        }
    }

    /**
     * 浏览器声明的 Content-Type 不可靠时，用扩展名映射到规范 MIME。
     *
     * <p>仅在空串或 {@code application/octet-stream} 时回填，其它错误类型仍按原样校验拒绝。</p>
     */
    static String resolveContentType(String extension, String declaredContentType) {
        String normalized = declaredContentType == null
                ? ""
                : declaredContentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank() && !"application/octet-stream".equals(normalized)) {
            return normalized;
        }
        Set<String> known = EXTENSION_CONTENT_TYPES.get(
                extension == null ? "" : extension.toLowerCase(Locale.ROOT));
        if (known == null || known.isEmpty()) {
            return normalized;
        }
        return known.iterator().next();
    }

    private static void validateFileFamily(String extension, String contentType, byte[] content) {
        String normalizedContentType = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        Set<String> compatibleTypes = EXTENSION_CONTENT_TYPES.get(extension);
        if (compatibleTypes == null || !compatibleTypes.contains(normalizedContentType)) {
            throw new InvalidRequestException(
                    "扩展名 %s 与内容类型 %s 不匹配"
                            .formatted(extension, normalizedContentType));
        }
        if (!hasExpectedSignature(extension, content)) {
            throw new InvalidRequestException(
                    "文件内容与扩展名 %s 不匹配".formatted(extension));
        }
    }

    private static boolean hasExpectedSignature(String extension, byte[] content) {
        return switch (extension) {
            case "png" -> startsWith(content, PNG_SIGNATURE);
            case "jpg", "jpeg" -> startsWith(content, JPEG_SIGNATURE);
            case "gif" -> startsWith(content, GIF87A_SIGNATURE)
                    || startsWith(content, GIF89A_SIGNATURE);
            case "webp" -> startsWith(content, RIFF_SIGNATURE)
                    && startsAt(content, 8, WEBP_SIGNATURE);
            case "bmp" -> startsWith(content, BMP_SIGNATURE);
            case "pdf" -> startsWith(content, PDF_SIGNATURE);
            case "wav" -> startsWith(content, RIFF_SIGNATURE) && startsAt(content, 8, ascii("WAVE"));
            case "ogg" -> startsWith(content, OGG_SIGNATURE);
            case "m4a" -> startsAt(content, 4, ascii("ftyp"));
            case "webm" -> startsWith(content, WEBM_SIGNATURE);
            case "mp3" -> startsWith(content, ascii("ID3"))
                    || (content != null && content.length >= 2
                    && (content[0] & 0xff) == 0xff && (content[1] & 0xe0) == 0xe0);
            default -> true;
        };
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return startsAt(content, 0, signature);
    }

    private static boolean startsAt(byte[] content, int offset, byte[] signature) {
        if (content == null || offset < 0 || content.length < offset + signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[offset + index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Stores bytes the platform produced itself and registers them as an attachment of the caller.
     *
     * <p>Generated images go through here rather than through {@link #upload} so that they end up as
     * ordinary {@code uploaded_files} rows: the response then carries the same {@code /content} URL
     * as an upload, the ownership checks are the ones already in force, and a generated image can be
     * attached to a conversation later without a second code path.</p>
     *
     * <p>Blob first, metadata second, same as an upload: a failed insert removes the blob again.</p>
     *
     * @param conversationId optional thread to attach the result to, validated against the owner
     */
    @Transactional
    public UploadedFileResponse storeGenerated(CurrentUser currentUser, byte[] content, String extension,
                                               String mimeType, String originalName,
                                               FileAssetType assetType, Long conversationId) {
        return storeGenerated(currentUser, content, extension, mimeType, originalName, assetType, null,
                conversationId);
    }

    /**
     * Same as above, plus the feature that produced the bytes.
     *
     * <p>{@code source} is what makes a per-page work list possible: without it every
     * {@link FileAssetType#GENERATED} row looks identical, so the drawing gallery ended up listing
     * the image-generation page's and the smart-editing workbench's output as its own.</p>
     *
     * @param source producing feature, or {@code null} when the caller has no meaningful one (an
     *               agent-generated document, for example) — such rows appear in no work list
     */
    @Transactional
    public UploadedFileResponse storeGenerated(CurrentUser currentUser, byte[] content, String extension,
                                               String mimeType, String originalName,
                                               FileAssetType assetType, FileSource source,
                                               Long conversationId) {
        return storeGenerated(currentUser, content, extension, mimeType, originalName, assetType,
                source, conversationId, null);
    }

    /**
     * Same as above, plus the prompt that produced the picture.
     *
     * <p>Works are listed across features since V88: the image-generation page, AI drawing, the
     * canvas and the smart-editing workbench all read the same rows. A card can therefore no longer
     * rely on its own page's local prompt cache, so the prompt is stored on the row itself.</p>
     */
    @Transactional
    public UploadedFileResponse storeGenerated(CurrentUser currentUser, byte[] content, String extension,
                                               String mimeType, String originalName,
                                               FileAssetType assetType, FileSource source,
                                               Long conversationId, String prompt) {
        return storeGenerated(currentUser, content, extension, mimeType, originalName, assetType,
                source, conversationId, prompt, null);
    }

    /**
     * Full form: also records which image model produced the bytes (V91).
     *
     * <p>Work cards used to read the model name from the page's own localStorage cache keyed by
     * file id. That cache is per browser, so a cleared cache, another device or any older row left
     * the card with nothing and it rendered 「未知模型」. The row is the only durable place for it.</p>
     *
     * @param model upstream model key, or {@code null} when the caller cannot name one
     */
    @Transactional
    public UploadedFileResponse storeGenerated(CurrentUser currentUser, byte[] content, String extension,
                                               String mimeType, String originalName,
                                               FileAssetType assetType, FileSource source,
                                               Long conversationId, String prompt, String model) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("生成的图片内容为空");
        }

        long limit = storageProperties.getMaxFileSize().toBytes();
        if (content.length > limit) {
            throw new PayloadTooLargeException(
                    "生成的图片超过 %d 字节".formatted(limit));
        }
        if (!storageProperties.isExtensionAllowed(extension)) {
            throw new InvalidRequestException("不支持的文件扩展名：%s".formatted(extension));
        }
        if (!storageProperties.isContentTypeAllowed(mimeType)) {
            throw new InvalidRequestException("不支持的文件类型：%s".formatted(mimeType));
        }

        requireStorageQuota(currentUser, content.length);
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        validateFileFamily(normalizedExtension, mimeType, content);
        if (conversationId != null) {
            // throws 404 when the thread is unknown or belongs to another account
            conversationService.requireOwned(conversationId, currentUser.userId());
        }

        String name = sanitizeName(originalName);
        StoredObject stored = storage.store(new ByteArrayInputStream(content), normalizedExtension);
        try {
            UploadedFileEntity entity = repository.save(new UploadedFileEntity(
                    currentUser.userId(),
                    conversationId,
                    stored.storageKey(),
                    name,
                    mimeType,
                    normalizedExtension,
                    assetType,
                    source,
                    stored.sizeBytes(),
                    stored.checksumSha256(),
                    prompt,
                    model));
            return UploadedFileResponse.from(entity);
        } catch (RuntimeException exception) {
            deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    /**
     * Streaming counterpart of {@link #storeGenerated(CurrentUser, byte[], String, String, String, Long)}.
     *
     * <p>Generated videos are tens of megabytes, so the bytes are handed to the blob store as a
     * stream instead of being buffered in the heap. Everything else — the allow lists, the size
     * limit and the conversation ownership check — is identical, deliberately: a generated video is
     * an ordinary attachment afterwards.</p>
     *
     * @param content stream consumed and closed by the storage implementation
     * @param size    expected number of bytes, or a negative value when unknown
     */
    @Transactional
    public UploadedFileResponse storeGenerated(CurrentUser currentUser, InputStream content, long size,
                                               String extension, String mimeType, String originalName,
                                               Long conversationId) {
        if (content == null) {
            throw new InvalidRequestException("生成的文件内容为空");
        }

        long limit = storageProperties.getMaxFileSize().toBytes();
        if (size == 0) {
            throw new InvalidRequestException("生成的文件内容为空");
        }
        if (size > limit) {
            throw new PayloadTooLargeException(
                    "生成的文件超过 %d 字节".formatted(limit));
        }
        if (!storageProperties.isExtensionAllowed(extension)) {
            throw new InvalidRequestException("不支持的文件扩展名：%s".formatted(extension));
        }
        if (!storageProperties.isContentTypeAllowed(mimeType)) {
            throw new InvalidRequestException("不支持的文件类型：%s".formatted(mimeType));
        }

        // size 为负表示调用方不知道长度，此时按 0 处理：只有「已经满额」才会被拦下
        requireStorageQuota(currentUser, Math.max(0, size));
        if (conversationId != null) {
            conversationService.requireOwned(conversationId, currentUser.userId());
        }

        String name = sanitizeName(originalName);
        StoredObject stored = storage.store(content, extension);
        if (stored.sizeBytes() > limit) {
            // The stream turned out longer than advertised; refuse it before it is registered.
            deleteQuietly(stored.storageKey());
            throw new PayloadTooLargeException(
                    "生成的文件超过 %d 字节".formatted(limit));
        }
        try {
            UploadedFileEntity entity = repository.save(new UploadedFileEntity(
                    currentUser.userId(),
                    conversationId,
                    stored.storageKey(),
                    name,
                    mimeType,
                    extension,
                    FileAssetType.GENERATED,
                    stored.sizeBytes(),
                    stored.checksumSha256()));
            return UploadedFileResponse.from(entity);
        } catch (RuntimeException exception) {
            deleteQuietly(stored.storageKey());
            throw exception;
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (StorageException exception) {
            log.warn("Unable to remove blob '{}': {}", storageKey, exception.getMessage());
        }
    }

    /**
     * Reduces a client supplied name to a bare file name. Path separators are stripped so nothing
     * like {@code ../../evil.png} can influence where the blob ends up.
     */
    static String sanitizeName(String originalName) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        int separator = name.lastIndexOf('/');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.strip().replaceAll("[\\p{Cntrl}]", "");
        if (name.isEmpty()) {
            return FALLBACK_NAME;
        }
        // keep the tail so the extension survives a truncation
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(name.length() - MAX_NAME_LENGTH);
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * Takes a work out of one page's list without touching the picture.
     *
     * <p>Used where the same picture is shown as <em>material</em> (smart editing): the user wants
     * it gone from here, not gone from every gallery. Deleting is reserved for the pages that own
     * the work — image generation and AI drawing — which is why this does not remove the row.</p>
     *
     * <p>Idempotent: removing an already-removed work succeeds.</p>
     */
    @Transactional
    public void hideWork(CurrentUser currentUser, Long fileId, String scope) {
        String resolvedScope = requireScope(scope);
        UploadedFileEntity file = requireOwned(fileId, currentUser);
        if (hiddenWorkRepository.findByUserIdAndFileIdAndScope(
                currentUser.userId(), file.getId(), resolvedScope).isPresent()) {
            return;
        }
        hiddenWorkRepository.save(
                new HiddenWorkEntity(currentUser.userId(), file.getId(), resolvedScope));
    }

    /** Puts a removed work back into that page's list. */
    @Transactional
    public void unhideWork(CurrentUser currentUser, Long fileId, String scope) {
        hiddenWorkRepository.deleteByUserIdAndFileIdAndScope(
                currentUser.userId(), fileId, requireScope(scope));
    }

    /**
     * Only pages that present works as material may hide them. A typo must not silently hide a
     * picture from a list nobody named.
     */
    private static String requireScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new InvalidRequestException("scope 不能为空");
        }
        String normalized = scope.strip().toLowerCase(Locale.ROOT);
        if (!HIDE_SCOPES.contains(normalized)) {
            throw new InvalidRequestException("scope 取值非法：" + scope);
        }
        return normalized;
    }

}
