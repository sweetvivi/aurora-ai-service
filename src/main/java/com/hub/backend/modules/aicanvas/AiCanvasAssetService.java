package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.config.properties.AiCanvasProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.common.exception.StorageException;
import com.hub.backend.infra.queue.TaskMessage;
import com.hub.backend.infra.queue.TaskQueue;
import com.hub.backend.infra.queue.TaskQueues;
import com.hub.backend.infra.storage.FileStorage;
import com.hub.backend.infra.storage.StoredObject;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.AssetResponse;
import com.hub.backend.modules.aicanvas.entity.AiCanvasAssetEntity;
import com.hub.backend.modules.aicanvas.entity.AiCanvasWorkflowEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasAssetRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasWorkflowRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.toUtc;

/**
 * AI Canvas material library backed by the platform blob store.
 */
@Service
public class AiCanvasAssetService {

    private static final Logger log = LoggerFactory.getLogger(AiCanvasAssetService.class);

    private static final Set<String> CATEGORIES = Set.of(
            "character", "scene", "object", "style", "sound", "other");

    /**
     * 允许的扩展名 -> 规范 MIME。命中任一白名单（扩展名或 MIME）即视为可上传，
     * 因此 {@code .jfif} / {@code image/jpg} 这类同一格式的不同写法都能通过。
     */
    private static final Map<String, String> EXTENSION_MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpe", "image/jpeg"),
            Map.entry("jfif", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("avif", "image/avif"),
            Map.entry("apng", "image/apng"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("m4v", "video/x-m4v"),
            Map.entry("mpeg", "video/mpeg"),
            Map.entry("mpg", "video/mpeg"),
            Map.entry("3gp", "video/3gpp"),
            Map.entry("ogv", "video/ogg"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("aac", "audio/aac"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("flac", "audio/flac"),
            Map.entry("opus", "audio/opus"),
            Map.entry("weba", "audio/webm"),
            Map.entry("aif", "audio/aiff"),
            Map.entry("aiff", "audio/aiff"));

    /** 规范 MIME -> 落库扩展名（无扩展名或扩展名与 MIME 冲突时的回退值）。 */
    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/avif", "avif"),
            Map.entry("image/apng", "apng"),
            Map.entry("image/svg+xml", "svg"),
            Map.entry("image/x-icon", "ico"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("video/x-msvideo", "avi"),
            Map.entry("video/x-matroska", "mkv"),
            Map.entry("video/x-m4v", "m4v"),
            Map.entry("video/mpeg", "mpeg"),
            Map.entry("video/3gpp", "3gp"),
            Map.entry("video/ogg", "ogv"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/aac", "aac"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/flac", "flac"),
            Map.entry("audio/opus", "opus"),
            Map.entry("audio/webm", "weba"),
            Map.entry("audio/aiff", "aiff"));

    /**
     * 部分客户端上报的是历史 / 别名 MIME（例如 {@code image/jpg}），
     * 这里统一收敛成规范 MIME 后再落库，保证读取时 {@code Content-Type} 标准。
     */
    private static final Map<String, String> CANONICAL_MIME = Map.ofEntries(
            Map.entry("image/jpg", "image/jpeg"),
            Map.entry("image/pjpeg", "image/jpeg"),
            Map.entry("image/x-png", "image/png"),
            Map.entry("image/x-ms-bmp", "image/bmp"),
            Map.entry("image/vnd.microsoft.icon", "image/x-icon"),
            Map.entry("audio/x-wav", "audio/wav"),
            Map.entry("audio/x-m4a", "audio/mp4"),
            Map.entry("audio/x-aiff", "audio/aiff"));

    private static final Set<String> EXTENSIONS = EXTENSION_MIME.keySet();

    private static final Set<String> ASSET_TYPES = Set.of("image", "video", "audio");

    private final AiCanvasAssetRepository repository;
    private final AiCanvasWorkflowRepository workflowRepository;
    private final FileStorage storage;
    private final AiCanvasProperties properties;
    private final FileService fileService;
    private final TaskQueue taskQueue;

    public AiCanvasAssetService(AiCanvasAssetRepository repository,
                                AiCanvasWorkflowRepository workflowRepository,
                                FileStorage storage,
                                AiCanvasProperties properties,
                                FileService fileService,
                                TaskQueue taskQueue) {
        this.repository = repository;
        this.workflowRepository = workflowRepository;
        this.storage = storage;
        this.properties = properties;
        this.fileService = fileService;
        this.taskQueue = taskQueue;
    }

    @Transactional(readOnly = true)
    public List<AssetResponse> list(CurrentUser currentUser, String category) {
        String filter = category == null || category.isBlank() ? null : category.strip();
        if (filter != null) {
            requireCategory(filter);
        }
        List<AiCanvasAssetEntity> assets = filter == null
                ? repository.findByUserIdAndStatusOrderByCreatedAtDesc(
                        currentUser.userId(), AiCanvasAssetEntity.STATUS_ACTIVE)
                : repository.findByUserIdAndCategoryAndStatusOrderByCreatedAtDesc(
                        currentUser.userId(), filter, AiCanvasAssetEntity.STATUS_ACTIVE);
        return assets.stream().map(this::toResponse).toList();
    }

    /**
     * 素材上传。
     *
     * <p>**不开事务**：blob 落盘期间不占连接（池只有 10 条）。落库由 {@code persist} 里的
     * {@code saveAndFlush} 用自己的短事务完成，失败时由 {@code persist} 删掉已写入的 blob。</p>
     */
    public AssetResponse upload(CurrentUser currentUser, MultipartFile file, String category,
                                String name, String prompt, String model) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("文件内容为空");
        }
        long limit = properties.getMaxAssetSize().toBytes();
        if (file.getSize() > limit) {
            throw new PayloadTooLargeException(
                    "AI Canvas asset exceeds the %d byte limit".formatted(limit));
        }

        String originalName = sanitizeName(name == null || name.isBlank()
                ? file.getOriginalFilename()
                : name);
        String mimeType = resolveMimeType(originalName, file.getContentType());
        String extension = resolveExtension(originalName, mimeType);
        String type = assetType(mimeType);
        ensureQuota(currentUser, file.getSize());

        StoredObject stored;
        try (InputStream input = file.getInputStream()) {
            stored = storage.store(input, extension);
        } catch (IOException exception) {
            throw new StorageException("无法读取画布素材", exception);
        }
        return persist(currentUser, stored, originalName, category, type,
                mimeType, extension, prompt, model);
    }

    @Transactional
    public AssetResponse storeGenerated(CurrentUser currentUser, byte[] content,
                                        String originalName, String category,
                                        String mimeType, String prompt, String model) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("生成的画布素材内容为空");
        }
        if (content.length > properties.getMaxAssetSize().toBytes()) {
            throw new PayloadTooLargeException("生成的画布素材超过大小上限");
        }
        String name = sanitizeName(originalName);
        String resolvedMime = resolveMimeType(name, mimeType);
        String extension = resolveExtension(name, resolvedMime);
        ensureQuota(currentUser, content.length);
        StoredObject stored = storage.store(new ByteArrayInputStream(content), extension);
        return persist(currentUser, stored, name, category, assetType(resolvedMime),
                resolvedMime, extension, prompt, model);
    }

    /**
     * Adds an existing attachment to the material library without copying its bytes.
     *
     * <p>The generation pages used to download every picture they had just produced and upload it
     * again, so one picture occupied space in both {@code uploaded_files} and the canvas library,
     * and deleting the work left an unreachable copy behind. A referencing row keeps only a pointer,
     * so the picture is counted — and reclaimed — exactly once.</p>
     *
     * <p>Idempotent: the caller re-syncs whenever its local marker is missing, and a second call
     * must return the existing material rather than create a twin.</p>
     */
    @Transactional
    public AssetResponse link(CurrentUser currentUser, Long fileId, String category,
                              String name, String prompt, String model) {
        if (fileId == null || fileId <= 0) {
            throw new InvalidRequestException("附件 ID 不能为空");
        }
        requireCategory(category);
        Optional<AiCanvasAssetEntity> existing =
                repository.findFirstByUserIdAndSourceFileIdAndStatus(
                        currentUser.userId(), fileId, AiCanvasAssetEntity.STATUS_ACTIVE);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        // 404 when the attachment is unknown or belongs to someone else — same rule as any read.
        UploadedFileResponse file = fileService.get(fileId, currentUser);
        String resolvedName = sanitizeName(
                name == null || name.isBlank() ? file.originalName() : name);
        String mimeType;
        try {
            mimeType = resolveMimeType(resolvedName, file.mimeType());
        } catch (InvalidRequestException exception) {
            // 素材库只收图片 / 视频 / 音频；把「哪个附件、什么类型」说清楚，比透传
            // 底层那句 "Unsupported AI Canvas asset content type" 有用。
            throw new InvalidRequestException(
                    "素材库只收图片、视频和音频，附件 %d 是 %s，无法加入"
                            .formatted(fileId, file.mimeType()));
        }
        String extension = resolveExtension(resolvedName, mimeType);
        // 调用方给的展示名可能不带扩展名（同步链路不读字节，猜 MIME 只会猜错），
        // 用附件自己的类型补上，下载时文件名才完整。
        resolvedName = withExtension(resolvedName, extension);

        // saveAndFlush 而不是 save：@CreationTimestamp 要等 flush 才有值，否则首次响应里
        // 少了 createdAt，调用方按时间排序时会和后续的重复请求表现不一致。
        AiCanvasAssetEntity asset = repository.saveAndFlush(AiCanvasAssetEntity.referencing(
                AiCanvasJsonCodec.newId("asset"),
                currentUser.userId(),
                fileId,
                resolvedName,
                category,
                assetType(mimeType),
                mimeType,
                extension,
                file.sizeBytes(),
                trimToNull(prompt),
                trimToNull(model)));
        return toResponse(asset);
    }

    @Transactional(readOnly = true)
    public DownloadedAsset download(CurrentUser currentUser, String key) {
        AiCanvasAssetEntity asset = requireOwned(currentUser, key);
        if (asset.referencesFile()) {
            return new DownloadedAsset(asset, referencedResource(currentUser, asset));
        }
        return new DownloadedAsset(asset, storage.load(asset.getStorageKey()));
    }

    /**
     * Reads the bytes of a material that points at an attachment.
     *
     * <p>The attachment can be deleted after the material was linked. That is a normal state, not a
     * lost row, so the message names the cause instead of surfacing the attachment lookup error.</p>
     */
    private Resource referencedResource(CurrentUser currentUser, AiCanvasAssetEntity asset) {
        try {
            return fileService.download(asset.getSourceFileId(), currentUser).resource();
        } catch (NotFoundException exception) {
            throw new NotFoundException(
                    "该素材引用的是已删除的作品（附件 %d），请重新生成后再加入素材库"
                            .formatted(asset.getSourceFileId()));
        }
    }

    /**
     * Drops the material rows that referenced a work the user really deleted.
     *
     * <p>A reference row keeps no bytes, so deleting the attachment silently turns the card into
     * one that cannot be opened. The file module publishes this event instead of calling back into
     * the canvas module, so the dependency stays one-way.</p>
     */
    @org.springframework.context.event.EventListener
    @Transactional
    public void onFileDeleted(com.hub.backend.modules.file.event.FileDeletedEvent event) {
        repository.findByUserIdAndSourceFileIdAndStatus(
                        event.userId(), event.fileId(), AiCanvasAssetEntity.STATUS_ACTIVE)
                .forEach(AiCanvasAssetEntity::markDeleted);
    }

    @Transactional
    public void delete(CurrentUser currentUser, String key) {
        AiCanvasAssetEntity asset = requireOwned(currentUser, key);
        asset.markDeleted();
        workflowRepository.findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
                        currentUser.userId(), AiCanvasWorkflowEntity.STATUS_ACTIVE)
                .forEach(workflow -> workflow.clearCover(key));
        if (asset.referencesFile()) {
            // 素材库把作品当素材展示，删除素材只从这里移除，原图与其它页面的作品列表都不动。
            // 真要删掉图片，去图片生成或 AI 绘画删（那里才是作品的归属页）。
            // 反过来，作品被真删时由 onFileDeleted 清掉这条引用，不会留下打不开的卡片。
            return;
        }
        // 删盘不再在事务里做：失败就是永久孤儿，成功但事务回滚又会让行指向不存在的文件。
        // 与删行为同一事务地投一条消息，由队列重试到底（删除幂等，重复投递无害）。
        taskQueue.publish(TaskMessage.of(TaskQueues.STORAGE_DELETE, asset.getStorageKey()));
    }

    public AssetResponse toResponse(AiCanvasAssetEntity asset) {
        return new AssetResponse(
                asset.getKey(),
                asset.getName(),
                asset.getCategory(),
                asset.getType(),
                asset.getPrompt(),
                asset.getModel(),
                asset.getMimeType(),
                asset.getSizeBytes(),
                toUtc(asset.getCreatedAt()),
                asset.getSourceFileId(),
                "/api/v1/ai-canvas/assets/%s/content".formatted(asset.getKey()));
    }

    private AssetResponse persist(CurrentUser currentUser, StoredObject stored, String name,
                                  String category, String type, String mimeType,
                                  String extension, String prompt, String model) {
        requireCategory(category);
        try {
            AiCanvasAssetEntity asset = repository.saveAndFlush(new AiCanvasAssetEntity(
                    AiCanvasJsonCodec.newId("asset"),
                    currentUser.userId(),
                    stored.storageKey(),
                    name,
                    category,
                    type,
                    mimeType,
                    extension,
                    stored.sizeBytes(),
                    trimToNull(prompt),
                    trimToNull(model)));
            return toResponse(asset);
        } catch (RuntimeException exception) {
            try {
                storage.delete(stored.storageKey());
            } catch (StorageException cleanupFailure) {
                log.warn("Unable to remove orphaned AI Canvas asset '{}': {}",
                        stored.storageKey(), cleanupFailure.getMessage());
            }
            throw exception;
        }
    }

    private AiCanvasAssetEntity requireOwned(CurrentUser currentUser, String key) {
        return repository.findByKeyAndUserIdAndStatus(
                        key, currentUser.userId(), AiCanvasAssetEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "AI Canvas asset '%s' was not found".formatted(key)));
    }

    private void ensureQuota(CurrentUser currentUser, long incomingBytes) {
        long used = repository.sumOwnedSizeByUserIdAndStatus(
                currentUser.userId(), AiCanvasAssetEntity.STATUS_ACTIVE);
        long quota = properties.getUserStorageQuota().toBytes();
        if (incomingBytes > quota || used > quota - incomingBytes) {
            // 与附件配额（FileService.requireStorageQuota）同一套口径：先说清已用/上限，再给出动作。
            throw new PayloadTooLargeException(
                    "素材库空间不足（已用 %d MB，上限 %d MB），请先删除一些素材后重试"
                            .formatted(megabytes(used), megabytes(quota)));
        }
    }

    private static long megabytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0));
    }

    private static void requireCategory(String category) {
        if (category == null || !CATEGORIES.contains(category.strip())) {
            throw new InvalidRequestException(
                    "category must be character, scene, object, style, sound or other");
        }
    }

    /**
     * 解析并规范化素材 MIME：优先采用上传声明的类型（含别名收敛），
     * 声明缺失或不可识别时回退到扩展名推断；两者都不支持时抛 400。
     */
    private static String resolveMimeType(String name, String declaredMimeType) {
        String normalized = normalizeMime(declaredMimeType);
        String canonical = CANONICAL_MIME.getOrDefault(normalized, normalized);
        if (MIME_EXTENSIONS.containsKey(canonical)) {
            return canonical;
        }
        String guessed = EXTENSION_MIME.get(extensionOf(name));
        if (guessed != null) {
            return guessed;
        }
        String extension = extensionOf(name);
        String described = normalized.isEmpty()
                ? (extension.isEmpty() ? "(no extension)" : "." + extension)
                : declaredMimeType;
        throw new InvalidRequestException("不支持的文件格式，请上传常见图片或视频");
    }

    /**
     * 解析落库扩展名：扩展名命中白名单时原样保留，否则按 MIME 归一化，
     * 让没有扩展名或扩展名与 MIME 不一致的文件也能正常落库。
     */
    private static String resolveExtension(String name, String mimeType) {
        String supplied = extensionOf(name);
        if (EXTENSIONS.contains(supplied)) {
            return supplied;
        }
        return MIME_EXTENSIONS.get(mimeType);
    }

    /** 补上扩展名（已经对得上就不动），用于调用方只给展示名、不给后缀的情况。 */
    private static String withExtension(String name, String extension) {
        if (extension == null || extension.isBlank()) {
            return name;
        }
        String suffix = "." + extension.toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).endsWith(suffix) ? name : name + suffix;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String assetType(String mimeType) {
        String normalized = normalizeMime(mimeType);
        int slash = normalized.indexOf('/');
        String type = slash < 0 ? normalized : normalized.substring(0, slash);
        if (!ASSET_TYPES.contains(type)) {
            throw new InvalidRequestException("不支持的文件格式，请上传常见图片或视频");
        }
        return type;
    }

    private static String normalizeMime(String mimeType) {
        return mimeType == null
                ? ""
                : mimeType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeName(String value) {
        String name = value == null ? "canvas-asset" : value.replace('\\', '/');
        int separator = name.lastIndexOf('/');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.strip().replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank()) {
            name = "canvas-asset";
        }
        return name.length() <= 512 ? name : name.substring(name.length() - 512);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record DownloadedAsset(AiCanvasAssetEntity asset, Resource resource) {
    }
}
