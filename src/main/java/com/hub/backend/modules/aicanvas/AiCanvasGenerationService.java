package com.hub.backend.modules.aicanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.config.properties.AiCanvasProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.ImageEditingClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.GeneratedImage;
import com.hub.backend.infra.llm.dto.ImageEditFile;
import com.hub.backend.infra.llm.dto.ImageEditingCommand;
import com.hub.backend.infra.llm.dto.ImageGenerationResult;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.GenerationResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.LocalModelGenerateRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.LocalModelResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.TaskResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.VideoGenerationRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.VideoTaskListResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.VideoTaskResponse;
import com.hub.backend.modules.aicanvas.entity.AiCanvasTaskEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasTaskRepository;
import com.hub.backend.modules.aicanvas.AiCanvasVideoUrlDownloader.DownloadedVideo;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.FileSource;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.image.ImageGenerationRateLimiter;
import com.hub.backend.modules.image.ImageGenerationService;
import com.hub.backend.modules.image.ImageModelFallbackService;
import com.hub.backend.modules.image.dto.GeneratedImageResponse;
import com.hub.backend.modules.image.dto.ImageGenerationRequest;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.video.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.toUtc;

/**
 * Image/video generation façade for the canvas contract.
 */
@Service
public class AiCanvasGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AiCanvasGenerationService.class);

    private static final Pattern VIDEO_DATA_URL =
            Pattern.compile("^data:(video/[^;,]+);base64,(.+)$", Pattern.DOTALL);
    /** 画面比例 {@code a:b}，只用于兜底算出宽高，见 {@link #genericSize}。 */
    private static final Pattern RATIO_PATTERN = Pattern.compile("^(\\d{1,5}):(\\d{1,5})$");
    private static final int MAX_REFERENCE_IMAGES = 4;

    /** 与 {@code VideoGenerationRequest.mode} 的取值保持一致。 */
    private static final String VIDEO_MODE_TEXT = "text2video";
    private static final String VIDEO_MODE_IMAGE = "image2video";
    private static final String VIDEO_MODE_FIRST_LAST_FRAME = "first-last-frame";
    /** 参考图模式：多张图一起当参考，不锁首尾帧。 */
    private static final String VIDEO_MODE_REFERENCE = "reference2video";

    private final ImageGenerationService imageGenerationService;
    private final ImageEditingClient imageEditingClient;
    private final AiCanvasImageResolver imageResolver;
    private final AiCanvasAssetService assetService;
    private final AiCanvasFfmpegService ffmpegService;
    private final AiCanvasVideoUrlDownloader videoUrlDownloader;
    private final AiCanvasTaskRepository taskRepository;
    private final ModelUseResolver modelResolver;
    private final AiCanvasProperties properties;
    private final ObjectMapper objectMapper;
    private final UserRelayCredentialResolver relayCredentials;
    private final ImageGenerationRateLimiter imageRateLimiter;
    private final ImageModelFallbackService imageModelFallbackService;
    private final VideoGenerationService videoGenerationService;
    private final FileService fileService;

    public AiCanvasGenerationService(ImageGenerationService imageGenerationService,
                                     ImageEditingClient imageEditingClient,
                                     AiCanvasImageResolver imageResolver,
                                     AiCanvasAssetService assetService,
                                     AiCanvasFfmpegService ffmpegService,
                                     AiCanvasVideoUrlDownloader videoUrlDownloader,
                                     AiCanvasTaskRepository taskRepository,
                                     ModelUseResolver modelResolver,
                                     AiCanvasProperties properties,
                                     ObjectMapper objectMapper,
                                     UserRelayCredentialResolver relayCredentials,
                                     ImageModelFallbackService imageModelFallbackService,
                                     ImageGenerationRateLimiter imageRateLimiter,
                                     VideoGenerationService videoGenerationService,
                                     FileService fileService) {
        this.imageGenerationService = imageGenerationService;
        this.imageEditingClient = imageEditingClient;
        this.imageResolver = imageResolver;
        this.assetService = assetService;
        this.ffmpegService = ffmpegService;
        this.videoUrlDownloader = videoUrlDownloader;
        this.taskRepository = taskRepository;
        this.modelResolver = modelResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.relayCredentials = relayCredentials;
        this.imageRateLimiter = imageRateLimiter;
        this.imageModelFallbackService = imageModelFallbackService;
        this.videoGenerationService = videoGenerationService;
        this.fileService = fileService;
    }

    public GenerationResponse generateImage(CurrentUser currentUser,
                                            String prompt,
                                            String imageModel,
                                            String aspectRatio,
                                            String resolution,
                                            List<MultipartFile> imageFiles,
                                            String imageBase64,
                                            List<String> referenceUrls,
                                            String source) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidRequestException("选填提示词不能为空");
        }
        ProviderCredentials credentials = prepareImage(currentUser);
        // 画布接口被图片生成页复用做图生图，来源必须由调用方声明，否则两张不同的产物会挤进同一份作品列表。
        FileSource fileSource = resolveSource(source);

        List<AiCanvasImageResolver.LoadedImage> references = new ArrayList<>();
        if (imageFiles != null) {
            imageFiles.stream()
                    .filter(file -> file != null && !file.isEmpty())
                    .map(imageResolver::fromMultipart)
                    .forEach(references::add);
        }
        if (imageBase64 != null && !imageBase64.isBlank()) {
            references.add(imageResolver.decode(imageBase64));
        }
        if (referenceUrls != null) {
            referenceUrls.stream()
                    .filter(url -> url != null && !url.isBlank())
                    .map(url -> imageResolver.load(url, currentUser))
                    .forEach(references::add);
        }
        if (references.size() > MAX_REFERENCE_IMAGES) {
            throw new InvalidRequestException(
                    "最多支持 4 张参考图");
        }

        String size = imageSize(aspectRatio, resolution);
        String normalizedRatio = normalizedAspectRatio(aspectRatio);
        if (references.isEmpty()) {
            GeneratedImageResponse generated = imageGenerationService.generateWithRelayCredentials(
                            currentUser,
                            new ImageGenerationRequest(
                                    prompt.strip(), imageModel, size, null, 1, null,
                                    fileSource.value()),
                            // null = 按候选模型所属分组各自取钥匙；画布自己那份 credentials 只用于
                            // 图生图路径，图片模型常常只在另一个分组里有渠道。
                            null)
                    .images()
                    .get(0);
            return GenerationResponse.image(generated.url(), normalizedRatio);
        }

        return GenerationResponse.image(
                editImage(currentUser, imageModel, prompt.strip(), size, references, credentials,
                        fileSource),
                normalizedRatio);
    }

    public GenerationResponse generateImageFromReferences(CurrentUser currentUser,
                                                          String prompt,
                                                          String imageModel,
                                                          String aspectRatio,
                                                          String resolution,
                                                          List<AiCanvasImageResolver.LoadedImage> references) {
        return generateImageFromReferences(
                currentUser, prompt, imageModel, aspectRatio, resolution, references,
                prepareImage(currentUser));
    }

    GenerationResponse generateImageFromReferences(CurrentUser currentUser,
                                                   String prompt,
                                                   String imageModel,
                                                   String aspectRatio,
                                                   String resolution,
                                                   List<AiCanvasImageResolver.LoadedImage> references,
                                                   ProviderCredentials credentials) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidRequestException("提示词不能为空");
        }
        if (references != null && references.size() > MAX_REFERENCE_IMAGES) {
            throw new InvalidRequestException(
                    "最多支持 4 张参考图");
        }
        String size = imageSize(aspectRatio, resolution);
        if (references == null || references.isEmpty()) {
            GeneratedImageResponse generated = imageGenerationService.generateWithRelayCredentials(
                            currentUser,
                            new ImageGenerationRequest(
                                    prompt.strip(), imageModel, size, null, 1, null, "canvas"),
                            null)
                    .images()
                    .get(0);
            return GenerationResponse.image(generated.url(), normalizedAspectRatio(aspectRatio));
        }
        return GenerationResponse.image(
                editImage(currentUser, imageModel, prompt.strip(), size, references, credentials,
                        FileSource.CANVAS),
                normalizedAspectRatio(aspectRatio));
    }

    /**
     * Credentials for the image paths. Image models may sit on a differently scoped upstream key,
     * so this must not fall back to the chat key: with the relay disabled (local development) the
     * chat key is rejected by the gateway with {@code 404 model_not_found}. With the relay enabled
     * both resolvers return the same per-account key.
     */
    ProviderCredentials prepareImage(CurrentUser currentUser) {
        return relayCredentials.resolveImage(currentUser);
    }

    /**
     * 画布视频生成：把画布契约（首尾帧 base64 + videoModel）翻译成平台视频模块的契约
     * （参考图附件 ID + 模型 key），再交给 {@link VideoGenerationService} 排队执行。
     *
     * <p>画布节点里的「首帧 / 尾帧」是前端直接塞进请求体的值：可能是 data URL、裸 base64、
     * 平台附件地址，也可能是画布素材地址。平台视频模块只接受 {@code referenceFileId}，
     * 所以这里先把帧落成附件；帧本身就是平台附件的地址直接复用，避免重复占一份存储配额。</p>
     *
     * <p>视频是异步的：提交成功后只返回 taskId，结果由前端轮询 {@code GET /tasks/{taskId}}
     * （见 {@link #task}）。提交失败时把本次新落的附件删掉，避免余额不足这类失败在用户存储里
     * 留下孤儿文件。</p>
     */
    public GenerationResponse generateVideo(CurrentUser currentUser, VideoGenerationRequest request) {
        String motionReference = blankToNull(request.motionReferenceUrl());
        if (motionReference != null) {
            // 平台视频模块的视频生成链路没有动作参考字段，静默忽略会让用户拿到一段不跟动作的成片。
            throw new InvalidRequestException("画布暂不支持动作参考视频，请移除动作参考后重试");
        }

        List<Long> createdFileIds = new ArrayList<>();
        com.hub.backend.modules.video.dto.VideoTaskResponse task;
        try {
            Long referenceFileId = storeReferenceFrame(
                    currentUser, request.imageBase64(), "first-frame", createdFileIds);
            Long lastFrameFileId = storeReferenceFrame(
                    currentUser, request.lastFrameBase64(), "last-frame", createdFileIds);
            if (lastFrameFileId != null && referenceFileId == null) {
                throw new InvalidRequestException("尾帧必须和首帧一起使用，请补上首帧或去掉尾帧");
            }
            List<Long> referenceFileIds = storeReferenceImages(
                    currentUser, request.referenceImages(), createdFileIds);
            if (!referenceFileIds.isEmpty() && (referenceFileId != null || lastFrameFileId != null)) {
                // 两种输入方式的语义互斥：参考图不锁首尾，混在一起送上游只会被供应商静默忽略一半。
                throw new InvalidRequestException("参考图模式不能同时使用首帧或尾帧，请只选一种输入方式");
            }
            task = videoGenerationService.submit(currentUser, new com.hub.backend.modules.video.dto.VideoGenerationRequest(
                    request.prompt().strip(),
                    blankToNull(request.videoModel()),
                    autoAsNull(request.aspectRatio()),
                    autoAsNull(request.resolution()),
                    request.duration(),
                    referenceFileId,
                    lastFrameFileId,
                    referenceFileIds,
                    videoMode(referenceFileId, lastFrameFileId, referenceFileIds),
                    null));
        } catch (RuntimeException exception) {
            createdFileIds.forEach(fileId -> deleteFrameQuietly(fileId, currentUser));
            throw exception;
        }

        // 任务已经提交（可能已扣费），镜像行写失败不能反手把用户的这次生成判为失败。
        try {
            mirrorVideoTask(currentUser, task);
        } catch (RuntimeException exception) {
            log.warn("无法为画布记录视频任务 {} 的历史索引：{}", task.taskId(), exception.toString());
        }
        return GenerationResponse.pending(task.taskId());
    }

    public GenerationResponse trimVideo(CurrentUser currentUser, String videoDataUrl,
                                        double startTime, double endTime) {
        DecodedVideo decoded = decodeVideo(videoDataUrl);
        byte[] trimmed = ffmpegService.trim(decoded.content(), decoded.extension(), startTime, endTime);
        String name = "canvas-trim-%d-%d.mp4".formatted(
                Math.round(startTime * 1000), Math.round(endTime * 1000));
        String url = assetService.storeGenerated(
                        currentUser, trimmed, name, "scene", "video/mp4",
                        "Canvas video trim", null)
                .url();
        return GenerationResponse.video(url);
    }

    /**
     * 任务状态。
     *
     * <p>视频任务的真实状态由平台视频模块（{@code async_tasks}）维护，画布这张行只是该用户的
     * 历史索引，所以视频任务每次都回读一遍并把状态回写索引行，否则画布历史会一直停在
     * 「running」。索引行在视频模块里查不到（历史遗留、任务被删）时退回读行内结果。</p>
     *
     * <p>方法本身不开事务：视频模块的 {@code get} 是 {@code @Transactional} 的，它在查不到任务时
     * 抛 404，若共用一个事务，这个 404 会把事务标成 rollback-only，随后回写索引行就会以
     * {@code UnexpectedRollbackException} 收场。读、回写各自独立提交，语义也更清楚。</p>
     */
    public TaskResponse task(CurrentUser currentUser, String taskId) {
        AiCanvasTaskEntity task = taskRepository.findByTaskIdAndUserId(taskId, currentUser.userId())
                .orElseThrow(() -> new NotFoundException(
                        "画布任务 %s 不存在".formatted(taskId)));
        if (AiCanvasTaskEntity.TYPE_VIDEO.equals(task.getTaskType())) {
            latestVideoTask(task.getTaskId(), currentUser.userId())
                    .ifPresent(latest -> syncVideoTask(task, latest));
        }
        return taskResponse(task);
    }

    public VideoTaskListResponse videoTasks(CurrentUser currentUser) {
        List<VideoTaskResponse> tasks = taskRepository
                .findByUserIdAndTaskTypeOrderByCreatedAtDesc(
                        currentUser.userId(), AiCanvasTaskEntity.TYPE_VIDEO)
                .stream()
                .map(task -> videoTaskResponse(currentUser, task))
                .toList();
        return new VideoTaskListResponse(tasks);
    }

    /** 历史列表：未结束的行顺手对齐视频模块，已结束的行只读本地，避免每次刷新都多一轮查询。 */
    private VideoTaskResponse videoTaskResponse(CurrentUser currentUser, AiCanvasTaskEntity task) {
        if (!isTerminal(task.getStatus())) {
            latestVideoTask(task.getTaskId(), currentUser.userId())
                    .ifPresent(latest -> syncVideoTask(task, latest));
        }
        JsonNode result = readResult(task.getResultJson());
        return new VideoTaskResponse(
                task.getTaskId(),
                task.getPrompt(),
                toUtc(task.getCreatedAt()),
                textOrNull(result, "videoUrl"),
                task.getModelKey(),
                task.getStatus(),
                userFacingTaskError(task.getErrorMessage()));
    }

    private TaskResponse taskResponse(AiCanvasTaskEntity task) {
        JsonNode result = readResult(task.getResultJson());
        return new TaskResponse(
                task.getTaskId(),
                task.getStatus(),
                task.getProgress(),
                textOrNull(result, "url"),
                textOrNull(result, "videoUrl"),
                userFacingTaskError(task.getErrorMessage()));
    }

    /** 视频模块的任务视图；任务不存在（被删或从来不属于视频模块）时返回空。 */
    private Optional<com.hub.backend.modules.video.dto.VideoTaskResponse> latestVideoTask(
            String taskId, Long userId) {
        try {
            return Optional.of(videoGenerationService.get(taskId, userId));
        } catch (NotFoundException exception) {
            return Optional.empty();
        }
    }

    /**
     * 把视频模块的状态回写到画布索引行。只有真的变了才写，轮询每 5 秒一次，不能每次都产生
     * 一条 UPDATE。失败原因已经是用户可见的短中文，直接落库。
     */
    private void syncVideoTask(AiCanvasTaskEntity task,
                               com.hub.backend.modules.video.dto.VideoTaskResponse latest) {
        String status = canvasVideoStatus(latest.status());
        String error = canvasVideoError(latest);
        String resultJson = latest.videoUrl() == null || latest.videoUrl().isBlank()
                ? task.getResultJson()
                : videoResultJson(latest.videoUrl());
        if (Objects.equals(status, task.getStatus())
                && Objects.equals(error, task.getErrorMessage())
                && Objects.equals(resultJson, task.getResultJson())) {
            return;
        }
        task.update(status, latest.progress(), resultJson, error);
        taskRepository.save(task);
    }

    private void mirrorVideoTask(CurrentUser currentUser,
                                 com.hub.backend.modules.video.dto.VideoTaskResponse task) {
        taskRepository.save(new AiCanvasTaskEntity(
                task.taskId(), currentUser.userId(), AiCanvasTaskEntity.TYPE_VIDEO, null,
                task.model(), task.model(), task.prompt(),
                canvasVideoStatus(task.status()), task.progress(), null, null));
    }

    private static String canvasVideoStatus(String platformStatus) {
        return switch (platformStatus == null ? "" : platformStatus.toLowerCase(Locale.ROOT)) {
            case "succeeded" -> AiCanvasTaskEntity.STATUS_SUCCEEDED;
            case "failed", "cancelled" -> AiCanvasTaskEntity.STATUS_FAILED;
            default -> AiCanvasTaskEntity.STATUS_RUNNING;
        };
    }

    private static String canvasVideoError(
            com.hub.backend.modules.video.dto.VideoTaskResponse task) {
        if ("cancelled".equalsIgnoreCase(task.status())) {
            return "任务已取消";
        }
        return blankToNull(task.errorMessage());
    }

    private static boolean isTerminal(String status) {
        return AiCanvasTaskEntity.STATUS_SUCCEEDED.equals(status)
                || AiCanvasTaskEntity.STATUS_FAILED.equals(status);
    }

    private static String userFacingTaskError(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UpstreamFailureText.describeAsync(raw);
    }

    public List<LocalModelResponse> localModels() {
        return properties.getLocalModels().stream()
                .map(model -> new LocalModelResponse(
                        model.getId(), model.getName(), model.getType(), model.getDescription()))
                .toList();
    }

    public GenerationResponse generateLocal(CurrentUser currentUser, LocalModelGenerateRequest request) {
        AiCanvasProperties.LocalModel model = properties.getLocalModels().stream()
                .filter(item -> item.getId() != null && item.getId().equals(request.model()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "本地模型 %s 不存在".formatted(request.model())));
        String type = model.getType() == null ? "" : model.getType().strip().toLowerCase(Locale.ROOT);
        if (!"image".equals(type)) {
            throw new ServiceUnavailableException(
                    "未配置本地 %s 模型".formatted(type));
        }
        return generateImage(
                currentUser, request.prompt(), request.model(), request.aspectRatio(),
                request.resolution(), List.of(), null, List.of(), FileSource.CANVAS.value());
    }

    private String editImage(CurrentUser currentUser,
                             String imageModel,
                             String prompt,
                             String size,
                             List<AiCanvasImageResolver.LoadedImage> references,
                             ProviderCredentials credentials,
                             FileSource source) {
        // 图生图的唯一上游出口。文生图走 ImageGenerationService，不在这里重复计数。
        imageRateLimiter.checkAndRecord(currentUser, 1);
        List<ModelUseResolver.Resolved> resolved = modelResolver.resolveAllForSite(
                currentUser.siteId(), imageModel, LlmModelEntity.TYPE_IMAGE);
        ImageGenerationResult result = imageModelFallbackService.invoke(
                        currentUser.siteId(),
                        resolved,
                        selected -> imageEditingClient.edit(new ImageEditingCommand(
                                selected.upstreamKey(),
                                prompt,
                                references.stream()
                                        .map(reference -> new ImageEditFile(
                                                reference.content(),
                                                "reference." + reference.extension(),
                                                reference.mimeType()))
                                        .toList(),
                                null,
                                size,
                                credentials)))
                .value();
        if (result.images().isEmpty()) {
            throw new ServiceUnavailableException("图片编辑失败：模型没有返回可用图片，请重试");
        }
        GeneratedImage image = result.images().get(0);
        return imageGenerationService
                .persistGeneratedImage(currentUser, image, 0, null, source, prompt,
                        resolved.get(0).requestKey())
                .url();
    }

    private JsonNode readResult(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("error", "Stored task result is invalid");
            return fallback;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    /**
     * 来源由调用方声明（画布页 / 图片生成页复用同一接口），非法值直接拒绝：静默回退会让产物
     * 落进错误的作品列表，排查时看不出问题。
     */
    private static FileSource resolveSource(String source) {
        if (source == null || source.isBlank()) {
            return FileSource.CANVAS;
        }
        FileSource resolved = FileSource.fromValueOrNull(source);
        if (resolved == null) {
            throw new InvalidRequestException("source 取值非法：" + source);
        }
        return resolved;
    }

    /**
     * 把画布节点上的首帧 / 尾帧变成平台附件 ID。
     *
     * <p>取值形态有三种：平台附件地址（直接复用，不必再复制一份字节）、画布素材或公网地址
     * （服务端读回字节）、data URL 或裸 base64（直接解码）。本次新落的附件记进
     * {@code createdFileIds}，提交失败时由调用方清理。</p>
     */
    private Long storeReferenceFrame(CurrentUser currentUser, String value, String slot,
                                     List<Long> createdFileIds) {
        String source = blankToNull(value);
        if (source == null) {
            return null;
        }
        Optional<Long> existingFileId = AiCanvasImageResolver.platformFileId(source);
        if (existingFileId.isPresent()) {
            // 归属校验在视频模块提交时还会再做一次，这里提前一次是为了读别人的附件时给 404 而不是 400。
            fileService.requireOwned(existingFileId.get(), currentUser);
            return existingFileId.get();
        }
        if (source.startsWith("blob:")) {
            throw new InvalidRequestException("参考帧图片无法读取，请在画布上重新上传后再生成");
        }
        AiCanvasImageResolver.LoadedImage image = isUrl(source)
                ? imageResolver.load(source, currentUser)
                : imageResolver.decode(source);
        UploadedFileResponse stored = fileService.storeGenerated(
                currentUser, image.content(), image.extension(), image.mimeType(),
                "canvas-video-%s.%s".formatted(slot, image.extension()),
                FileAssetType.REFERENCE, FileSource.CANVAS, null, null);
        createdFileIds.add(stored.id());
        return stored.id();
    }

    /**
     * 参考图模式：按数组顺序把每张图落成附件，顺序即「参考图 1、2…」。
     *
     * <p>全部读取失败时直接报错，避免把用户的参考图模式静默降级成文生视频。</p>
     */
    private List<Long> storeReferenceImages(CurrentUser currentUser, List<String> values,
                                            List<Long> createdFileIds) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Long> fileIds = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Long fileId = storeReferenceFrame(
                    currentUser, values.get(index), "reference-" + (index + 1), createdFileIds);
            if (fileId != null) {
                fileIds.add(fileId);
            }
        }
        if (fileIds.isEmpty()) {
            throw new InvalidRequestException("参考图无法读取，请在画布上重新上传后再生成");
        }
        return fileIds;
    }

    private static boolean isUrl(String value) {        return value.startsWith("/")
                || value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    private void deleteFrameQuietly(Long fileId, CurrentUser currentUser) {
        try {
            fileService.delete(fileId, currentUser);
        } catch (RuntimeException exception) {
            log.warn("清理画布视频参考帧附件 {} 失败：{}", fileId, exception.toString());
        }
    }

    private static String videoMode(Long referenceFileId, Long lastFrameFileId,
                                    List<Long> referenceFileIds) {
        if (referenceFileIds != null && !referenceFileIds.isEmpty()) {
            return VIDEO_MODE_REFERENCE;
        }
        if (lastFrameFileId != null) {
            return VIDEO_MODE_FIRST_LAST_FRAME;
        }
        return referenceFileId == null ? VIDEO_MODE_TEXT : VIDEO_MODE_IMAGE;
    }

    /** 画布把「Auto」当作「交给模型决定」，视频模块没有这个取值，等价于不传。 */
    private static String autoAsNull(String value) {
        String stripped = blankToNull(value);
        return stripped == null || "auto".equalsIgnoreCase(stripped) ? null : stripped;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private String videoResultJson(String videoUrl) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("videoUrl", videoUrl);
        return result.toString();
    }

    /**
     * 画面比例 → 上游尺寸。
     *
     * <p>显式列出的档位沿用各自的历史基准值（画布那套 16:9 是 1536x864，绘画页是 1344x768，
     * 两者比例相同、像素不同，改统一会动到已有产物的尺寸，所以只做加法）。</p>
     *
     * <p>新增档位对齐绘画页 {@code PaintingView.ratioOptions}：那边一共 13 个比例，
     * 这里以前只认 5 个，其余落在 default 里抛 400。结果是同一个 21:9，无参考图时正常、
     * 一带参考图就报「画面比例只能是…」，用户看到的就是「时好时坏」。</p>
     *
     * <p>default 不再直接抛错，交给 {@link #genericSize}：未列出的 {@code a:b} 现算，
     * 已经是 {@code 宽x高} 的原样透传，这样前端以后加比例不用再回来改后端。</p>
     */
    static String imageSize(String aspectRatio, String resolution) {
        String ratio = aspectRatio == null || aspectRatio.isBlank()
                ? "1:1"
                : aspectRatio.strip();
        if ("Auto".equalsIgnoreCase(ratio)) {
            return null;
        }
        int scale = switch (resolution == null ? "1K" : resolution.toUpperCase(Locale.ROOT)) {
            case "2K" -> 2;
            case "4K" -> 4;
            default -> 1;
        };
        return switch (ratio) {
            case "1:1" -> "%dx%d".formatted(1024 * scale, 1024 * scale);
            case "16:9" -> "%dx%d".formatted(1536 * scale, 864 * scale);
            case "9:16" -> "%dx%d".formatted(864 * scale, 1536 * scale);
            case "4:3" -> "%dx%d".formatted(1152 * scale, 864 * scale);
            case "3:4" -> "%dx%d".formatted(864 * scale, 1152 * scale);
            // 以下对齐绘画页的 1K 基准，避免带参考图重绘时被当成非法比例
            case "21:9" -> "%dx%d".formatted(1344 * scale, 576 * scale);
            case "2:1" -> "%dx%d".formatted(1344 * scale, 672 * scale);
            case "3:2" -> "%dx%d".formatted(1280 * scale, 832 * scale);
            case "5:4" -> "%dx%d".formatted(1120 * scale, 896 * scale);
            case "4:5" -> "%dx%d".formatted(896 * scale, 1120 * scale);
            case "2:3" -> "%dx%d".formatted(832 * scale, 1280 * scale);
            case "1:2" -> "%dx%d".formatted(672 * scale, 1344 * scale);
            default -> genericSize(ratio, scale);
        };
    }

    /**
     * 未显式列出的尺寸写法。
     *
     * <p>{@code 宽x高} 原样透传（上游对任意尺寸都能精确出图，实测 1344x576、896x1120 均逐像素一致）；
     * {@code a:b} 以 1024x1024 的面积按比例现算，宽高对齐到 8 的倍数，避免出现奇数边长。</p>
     */
    private static String genericSize(String ratio, int scale) {
        if (ratio.matches("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")) {
            return ratio;
        }
        Matcher matcher = RATIO_PATTERN.matcher(ratio);
        if (!matcher.matches()
                || Long.parseLong(matcher.group(1)) <= 0
                || Long.parseLong(matcher.group(2)) <= 0) {
            throw new InvalidRequestException(
                    "画面比例只能是 Auto、a:b 或 宽x高，例如 16:9 / 1024x1024");
        }
        long wide = Long.parseLong(matcher.group(1));
        long tall = Long.parseLong(matcher.group(2));
        long area = 1024L * 1024L * scale * scale;
        long width = Math.round(Math.sqrt(area * (double) wide / tall));
        long height = Math.round((double) width * tall / wide);
        return "%dx%d".formatted(alignTo8(width), alignTo8(height));
    }

    private static long alignTo8(long value) {
        return Math.max(8L, (value + 4) / 8 * 8);
    }

    private static String normalizedAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()
                || "Auto".equalsIgnoreCase(aspectRatio.strip())) {
            return null;
        }
        return aspectRatio.strip().replace(':', '/');
    }

    private DecodedVideo decodeVideo(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw new InvalidRequestException("videoDataUrl must not be blank");
        }
        String source = dataUrl.strip();
        if (source.regionMatches(true, 0, "http://", 0, 7)
                || source.regionMatches(true, 0, "https://", 0, 8)) {
            DownloadedVideo downloaded = videoUrlDownloader.download(source);
            return new DecodedVideo(downloaded.content(), downloaded.extension());
        }

        Matcher matcher = VIDEO_DATA_URL.matcher(source);
        if (!matcher.matches()) {
            throw new InvalidRequestException(
                    "videoDataUrl must be a base64 video data URL or a public http(s) video URL");
        }
        String mimeType = matcher.group(1).toLowerCase(Locale.ROOT);
        String extension = switch (mimeType) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> throw new InvalidRequestException(
                    "Unsupported video data URL content type: " + mimeType);
        };
        try {
            byte[] content = Base64.getDecoder().decode(matcher.group(2).replaceAll("\\s+", ""));
            if (content.length == 0) {
                throw new InvalidRequestException("videoDataUrl contains no video data");
            }
            long maxBytes = properties.getMaxAssetSize().toBytes();
            if (content.length > maxBytes) {
                throw new InvalidRequestException(
                        "videoDataUrl exceeds the %d byte limit".formatted(maxBytes));
            }
            return new DecodedVideo(content, extension);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("videoDataUrl contains invalid base64");
        }
    }

    private record DecodedVideo(byte[] content, String extension) {
    }
}
