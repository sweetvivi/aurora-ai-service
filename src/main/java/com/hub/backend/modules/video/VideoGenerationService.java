package com.hub.backend.modules.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.config.properties.VideoProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.UpstreamVideoStatus;
import com.hub.backend.infra.llm.dto.UpstreamVideoTask;
import com.hub.backend.infra.llm.dto.VideoGenerationCommand;
import com.hub.backend.infra.llm.video.VideoGenerationClient;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelCapabilityPublisher;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.dto.ModelCapabilities;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.provider.ProviderCredentialResolver;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.video.dto.VideoGenerationRequest;
import com.hub.backend.modules.video.dto.VideoTaskResponse;
import com.hub.backend.modules.video.entity.AsyncTaskEntity;
import com.hub.backend.modules.video.repository.AsyncTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Submits, tracks and finishes video generation jobs.
 *
 * <p>A submission is deliberately cheap: resolve the model, check ownership, write one
 * {@code async_tasks} row and hand the upstream call to the video executor. Nothing long-running
 * happens on the request thread, and the client gets a task id it can poll — video takes minutes,
 * far beyond a sensible HTTP request.</p>
 *
 * <p>Progress lives in the database rather than in memory so a restart does not lose in-flight
 * jobs; {@link VideoTaskPoller} picks them up again from the {@code RUNNING} state.</p>
 */
@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final String DEFAULT_EXTENSION = "mp4";
    private static final String DEFAULT_MIME = "video/mp4";

    private static final String MODE_TEXT_TO_VIDEO = "text2video";
    private static final String MODE_IMAGE_TO_VIDEO = "image2video";
    private static final String MODE_FIRST_LAST_FRAME = "first-last-frame";
    /**
     * 参考图模式：多张图一起作为参考（人物 / 场景 / 风格），不锁首尾帧。
     * 上游没有同名取值，由 {@code reference_images} 字段承载。
     */
    private static final String MODE_REFERENCE_TO_VIDEO = "reference2video";

    private final VideoGenerationClient videoClient;
    private final com.hub.backend.infra.llm.video.VideoUrlDownloader videoUrlDownloader;
    private final FileService fileService;
    private final ConversationService conversationService;
    private final ModelUseResolver modelResolver;
    private final ModelCapabilityPublisher capabilityPublisher;
    private final UserRelayCredentialResolver relayCredentials;
    private final ProviderCredentialResolver providerCredentials;
    private final AsyncTaskRepository tasks;
    private final LlmModelRepository llmModels;
    private final LlmProperties llmProperties;
    private final NewApiProperties newApiProperties;
    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;
    private final Executor videoTaskExecutor;
    private final EntityManager entityManager;
    /** Short commits around claim / record so the upstream HTTP call never holds a DB transaction. */
    private final TransactionTemplate requiresNewTx;

    public VideoGenerationService(VideoGenerationClient videoClient,
                                  com.hub.backend.infra.llm.video.VideoUrlDownloader videoUrlDownloader,
                                  FileService fileService,
                                  ConversationService conversationService,
                                  ModelUseResolver modelResolver,
                                  ModelCapabilityPublisher capabilityPublisher,
                                  UserRelayCredentialResolver relayCredentials,
                                  ProviderCredentialResolver providerCredentials,
                                  AsyncTaskRepository tasks,
                                  LlmModelRepository llmModels,
                                  LlmProperties llmProperties,
                                  NewApiProperties newApiProperties,
                                  VideoProperties videoProperties,
                                  ObjectMapper objectMapper,
                                  @Qualifier("videoTaskExecutor") Executor videoTaskExecutor,
                                  EntityManager entityManager,
                                  PlatformTransactionManager transactionManager) {
        this.videoClient = videoClient;
        this.videoUrlDownloader = videoUrlDownloader;
        this.fileService = fileService;
        this.conversationService = conversationService;
        this.modelResolver = modelResolver;
        this.capabilityPublisher = capabilityPublisher;
        this.relayCredentials = relayCredentials;
        this.providerCredentials = providerCredentials;
        this.tasks = tasks;
        this.llmModels = llmModels;
        this.llmProperties = llmProperties;
        this.newApiProperties = newApiProperties;
        this.videoProperties = videoProperties;
        this.objectMapper = objectMapper;
        this.videoTaskExecutor = videoTaskExecutor;
        this.entityManager = entityManager;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Validates and queues one job.
     *
     * <p>Everything that can be rejected without spending upstream quota happens here: the model
     * type, the reference file and the per-account concurrency limit.</p>
     *
     * @throws TooManyRequestsException when the account already has the maximum number of jobs
     */
    public VideoTaskResponse submit(CurrentUser currentUser, VideoGenerationRequest request) {
        ModelUseResolver.Resolved resolved = chooseCandidate(
                currentUser, request);

        List<Long> referenceFileIds = request.referenceFileIdsOrEmpty();
        if (request.referenceFileId() != null) {
            // 404 for somebody else's file, like every other ownership check.
            fileService.requireOwned(request.referenceFileId(), currentUser);
        }
        if (request.lastFrameFileId() != null) {
            fileService.requireOwned(request.lastFrameFileId(), currentUser);
        }
        for (Long fileId : referenceFileIds) {
            fileService.requireOwned(fileId, currentUser);
        }
        if (!referenceFileIds.isEmpty() && (request.referenceFileId() != null
                || request.lastFrameFileId() != null)) {
            throw new InvalidRequestException("参考图模式不能同时带首帧或尾帧，请只选一种输入方式");
        }
        if (request.conversationId() != null) {
            conversationService.requireOwned(request.conversationId(), currentUser.userId());
        }

        String mode = resolveMode(request, resolved);
        requireModeInputs(mode, request);
        requireCapabilitiesSupported(mode, request, resolved);

        long inFlight = tasks.countByUserIdAndTaskTypeAndStatusIn(
                currentUser.userId(), AsyncTaskEntity.TYPE_VIDEO_GENERATION,
                List.of(AsyncTaskEntity.STATUS_PENDING, AsyncTaskEntity.STATUS_RUNNING));
        if (inFlight >= videoProperties.getMaxConcurrentPerUser()) {
            throw new TooManyRequestsException(
                    "同时最多进行 %d 个视频生成任务，请稍后再试"
                            .formatted(videoProperties.getMaxConcurrentPerUser()),
                    videoProperties.getPollInterval().toSeconds());
        }

        // Video history lives in async_tasks (/video-tasks). Do not open a chat conversation or
        // append the prompt as a chat message — that leaked into the LLM dialogue history.
        // conversationId is optional, same as image generation: only attach when the client sends one.
        String taskKey = "vt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", request.prompt().strip());
        input.put("model", resolved.requestKey());
        input.put("modelId", resolved.id());
        input.put("siteId", currentUser.siteId());
        input.put("aspectRatio", request.aspectRatioOrNull());
        input.put("resolution", request.resolutionOrNull());
        input.put("duration", request.duration());
        input.put("mode", mode);
        input.put("referenceFileId", request.referenceFileId());
        input.put("lastFrameFileId", request.lastFrameFileId());
        input.put("referenceFileIds",
                MODE_REFERENCE_TO_VIDEO.equals(mode) ? referenceFileIds : List.of());
        input.put("conversationId", request.conversationId());

        AsyncTaskEntity task = tasks.save(new AsyncTaskEntity(
                currentUser.userId(), AsyncTaskEntity.TYPE_VIDEO_GENERATION, taskKey, json(input)));

        try {
            videoTaskExecutor.execute(() -> submitUpstream(task.getId(), currentUser, resolved, request));
        } catch (TaskRejectedException exception) {
            // The pool is saturated with other accounts' submissions. The row stays queued and the
            // poller submits it, so this is not a failure the caller has to see.
            log.warn("Video executor rejected task {}, leaving it queued for the poller", taskKey);
        }

        return toResponse(task);
    }

    /**
     * Picks the member that can actually honour this request.
     *
     * <p>A version group is an ordered chain of interchangeable models, and the front end is shown
     * the chain's <em>published</em> declaration (operator override, or the intersection of its
     * members). The first candidate is chosen by price, so it is frequently the strictest member:
     * on seedance-2.0 the cheapest is {@code fast-GT}, which only makes 10 or 15 秒. Taking
     * {@code resolve(...)} = first candidate meant a 7 秒 request that the page offered was then
     * rejected by our own validation, or worse, forwarded and rejected by the supplier.</p>
     *
     * <p>So the request parameters choose the member: the first candidate whose own declaration
     * accepts the requested duration, resolution, aspect ratio and reference-image count wins. If
     * none accepts it (the group's override promised something no member can do), the first
     * candidate is kept so the error names a real model instead of a group key.</p>
     */
    private ModelUseResolver.Resolved chooseCandidate(CurrentUser currentUser,
                                                      VideoGenerationRequest request) {
        String requested = request.modelOrNull();
        List<ModelUseResolver.Resolved> candidates = modelResolver.resolveAll(
                currentUser.siteId(), requested, llmProperties.getVideoModel(),
                LlmModelEntity.TYPE_VIDEO);
        if (candidates.size() <= 1) {
            return candidates.get(0);
        }
        int attached = request.referenceFileIdsOrEmpty().isEmpty()
                ? (request.referenceFileId() != null ? 1 : 0)
                        + (request.lastFrameFileId() != null ? 1 : 0)
                : request.referenceFileIdsOrEmpty().size();
        for (ModelUseResolver.Resolved candidate : candidates) {
            ModelCapabilities capabilities = capabilitiesOf(candidate.id());
            if (capabilities == null || accepts(capabilities, request, attached)) {
                return candidate;
            }
        }
        log.info("No member of '{}' accepts {}s / {} / {}; keeping the first candidate",
                requested, request.duration(), request.resolutionOrNull(),
                request.aspectRatioOrNull());
        return candidates.get(0);
    }

    /**
     * @return whether this member's own declaration accepts the requested parameters
     */
    static boolean accepts(ModelCapabilities capabilities,
                                   VideoGenerationRequest request,
                                   int attachedImageCount) {
        String aspectRatio = request.aspectRatioOrNull();
        if (aspectRatio != null && capabilities.aspectRatios() != null
                && !capabilities.aspectRatios().isEmpty()
                && !capabilities.aspectRatios().contains(aspectRatio)) {
            return false;
        }
        String resolution = request.resolutionOrNull();
        if (resolution != null && capabilities.resolutions() != null
                && !capabilities.resolutions().isEmpty()
                && !capabilities.resolutions().contains(resolution)) {
            return false;
        }
        Integer duration = request.duration();
        if (duration != null && !capabilities.allowsDuration(duration)) {
            return false;
        }
        return capabilities.maxReferenceImages() <= 0
                || attachedImageCount <= capabilities.maxReferenceImages();
    }

    /**
     * @throws NotFoundException when the account has no such task
     */
    @Transactional(readOnly = true)
    public VideoTaskResponse get(String taskKey, Long userId) {
        return toResponse(requireTask(taskKey, userId));
    }

    @Transactional(readOnly = true)
    public PageResponse<VideoTaskResponse> list(Long userId, Integer page, Integer size) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);
        Page<AsyncTaskEntity> result = tasks.findByUserIdAndTaskTypeOrderByCreatedAtDescIdDesc(
                userId, AsyncTaskEntity.TYPE_VIDEO_GENERATION, pageable);
        return PageResponse.of(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    /**
     * Requests cancellation. Cancelling a finished or unknown task is not an error, so a stop button
     * pressed at the wrong moment stays harmless.
     */
    @Transactional
    public void cancel(String taskKey, Long userId) {
        AsyncTaskEntity task = requireTask(taskKey, userId);
        if (task.isTerminal()) {
            return;
        }
        task.markCancelled(LocalDateTime.now());
        log.info("Video task {} cancelled by its owner", taskKey);
    }

    /**
     * Removes a finished task from the history and deletes its generated video when present.
     *
     * <p>Only a terminal task can be removed: deleting a running one would leave the upstream job
     * unobserved and the row that says to poll it gone. Reference images uploaded for the job are
     * left in place — they are user uploads that may still be useful outside this history row.</p>
     *
     * @throws com.hub.backend.common.exception.ConflictException when the task is still running
     */
    @Transactional
    public void delete(String taskKey, CurrentUser currentUser) {
        AsyncTaskEntity task = requireTask(taskKey, currentUser.userId());
        if (!task.isTerminal()) {
            throw new com.hub.backend.common.exception.ConflictException(
                    ApiMessages.VIDEO_TASK_STILL_RUNNING);
        }
        Long resultFileId = asLong(parse(task.getResultJson()).get("fileId"));
        tasks.delete(task);
        if (resultFileId != null) {
            deleteGeneratedVideoQuietly(resultFileId, currentUser);
        }
        log.info("Video task {} deleted (resultFileId={})", taskKey, resultFileId);
    }

    /** Best-effort: a missing or already-deleted attachment must not block clearing history. */
    private void deleteGeneratedVideoQuietly(Long fileId, CurrentUser currentUser) {
        try {
            fileService.delete(fileId, currentUser);
        } catch (NotFoundException exception) {
            log.debug("Video result file {} already gone while deleting task", fileId);
        } catch (RuntimeException exception) {
            log.warn("Failed to delete video result file {} while clearing task history: {}",
                    fileId, exception.toString());
        }
    }

    /**
     * Runs on the video executor: submits the job upstream and records the supplier task id.
     *
     * <p>Failures are written to the row rather than thrown: the client is polling, not waiting on
     * this future, so the only way it can learn about them is the task state.</p>
     *
     * <p>Claim happens before the HTTP call so the executor and the poller cannot both create an
     * upstream job for the same local row.</p>
     */
    void submitUpstream(Long taskId, CurrentUser currentUser,
                        ModelUseResolver.Resolved resolved, VideoGenerationRequest request) {
        Integer claimed = requiresNewTx.execute(status ->
                tasks.claimPendingForSubmit(taskId, LocalDateTime.now()));
        if (claimed == null || claimed == 0) {
            log.debug("Skip duplicate upstream submit for video task id={}", taskId);
            return;
        }

        try {
            // Inline the bytes as a data URL. A relative /api/v1/files/{id}/content path is only
            // reachable with the caller's Bearer token, so the New API relay cannot fetch it.
            String mode = resolveMode(request, resolved);
            String referenceImage = null;
            String lastFrameImage = null;
            List<String> referenceImages = List.of();
            if (MODE_REFERENCE_TO_VIDEO.equals(mode)) {
                referenceImages = request.referenceFileIdsOrEmpty().stream()
                        .map(fileId -> referenceImageDataUrl(fileId, currentUser))
                        .filter(url -> url != null && !url.isBlank())
                        .toList();
            } else {
                referenceImage = referenceImageDataUrl(request.referenceFileId(), currentUser);
                lastFrameImage = referenceImageDataUrl(request.lastFrameFileId(), currentUser);
            }
            // 中文提示词直接提交，不做英文翻译。原注释声称“上游视频模型只认英文”，但该说法
            // 从未核对过供应商文档，也没有实测记录；2026-11 核查：grok-imagine-video（本项目
            // 实际配置的 video-model）以及 Seedance / 可灵 / Wan / 海螺的文档与示例均支持中文
            // prompt。翻译反而有代价：多一次 LLM 调用、细节可能被改写、原生音频台词会被英化。
            // 若将来接入确证的英文-only 模型，再按模型能力启用 VideoPromptTranslator（类已保留）。
            UpstreamVideoTask upstream = videoClient.submit(new VideoGenerationCommand(
                    resolved.upstreamKey(),
                    request.prompt().strip(),
                    // The inferred mode, not just the one the client spelled out: a request that
                    // only carries a reference image still has to reach the supplier as
                    // image2video, otherwise the upstream has to guess from the image field.
                    mode,
                    request.aspectRatioOrNull(),
                    request.resolutionOrNull(),
                    request.duration(),
                    referenceImage,
                    lastFrameImage,
                    referenceImages,
                    credentialsFor(currentUser, resolved.id())));

            requiresNewTx.executeWithoutResult(status -> recordUpstreamSubmit(taskId, upstream.taskId()));
        } catch (LlmInvocationException exception) {
            fail(taskId, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Unexpected failure submitting video task {}", taskId, exception);
            fail(taskId, "The video request could not be submitted: " + exception.getMessage());
        }
    }

    /**
     * Persists the supplier task id after a successful claim. Ignores a second write if another
     * path already recorded one (defence in depth; claim should already prevent that).
     */
    private void recordUpstreamSubmit(Long taskId, String upstreamTaskId) {
        AsyncTaskEntity current = tasks.findById(taskId).orElse(null);
        if (current == null || current.isTerminal()) {
            return;
        }
        if (current.getProviderTaskId() != null && !current.getProviderTaskId().isBlank()) {
            log.warn("Video task {} already bound to upstream {}, ignoring {}",
                    current.getTaskKey(), current.getProviderTaskId(), upstreamTaskId);
            return;
        }
        current.markRunning(upstreamTaskId, LocalDateTime.now());
        // Local "submitted" floor so the UI moves before the supplier reports a percent.
        current.markProgress(Math.max(5, current.getProgress().intValue()));
        tasks.save(current);
        log.info("Video task {} submitted upstream as {}", current.getTaskKey(), upstreamTaskId);
    }

    /**
     * Polls one task. Called by {@link VideoTaskPoller} on its own schedule.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void poll(Long taskId) {
        AsyncTaskEntity task = tasks.findById(taskId).orElse(null);
        if (task == null || task.isTerminal()) {
            return;
        }

        if (isTimedOut(task)) {
            markFailedUserFacing(task, "Video generation timed out");
            tasks.save(task);
            return;
        }

        if (AsyncTaskEntity.STATUS_PENDING.equals(task.getStatus())) {
            // Queued but never handed to the executor (a restart between insert and submit).
            ModelUseResolver.Resolved resolved = resolveForPoll(task);
            if (resolved == null) {
                markFailedUserFacing(task, "The video model is no longer available");
                tasks.save(task);
                return;
            }
            CurrentUser owner = ownerOf(task);
            VideoGenerationRequest request = requestFromJson(task.getInputJson());
            if (request == null) {
                markFailedUserFacing(task, "The stored request could not be read");
                tasks.save(task);
                return;
            }
            // Claim commits in REQUIRES_NEW; detach so this transaction cannot flush the stale
            // PENDING snapshot back over the claimed RUNNING row.
            Long id = task.getId();
            entityManager.detach(task);
            submitUpstream(id, owner, resolved, request);
            return;
        }

        String providerTaskId = task.getProviderTaskId();
        if (providerTaskId == null || providerTaskId.isBlank()) {
            // Claim moved the row to RUNNING before the HTTP round-trip finished. Wait for
            // recordUpstreamSubmit (or the task timeout) instead of failing the job.
            log.debug("Video task {} is still submitting upstream", task.getTaskKey());
            return;
        }

        UpstreamVideoStatus status;
        try {
            status = videoClient.poll(pollCredentials(task), providerTaskId);
        } catch (LlmInvocationException | ServiceUnavailableException | PaymentRequiredException exception) {
            // A transient gateway error must not kill a job that may still finish: the poller will
            // try again on the next cycle, and the timeout above is the backstop. The same holds
            // for a relay key that is briefly unavailable.
            log.warn("Polling video task {} failed: {}", task.getTaskKey(), exception.getMessage());
            return;
        }

        switch (status.state()) {
            case SUCCEEDED -> finish(task, status.videoUrl());
            case FAILED -> {
                markFailedUserFacing(task, status.errorMessage() == null
                        ? "Video generation failed upstream" : status.errorMessage());
                tasks.save(task);
            }
            case RUNNING, QUEUED -> {
                int local = task.getProgress() == null ? 0 : task.getProgress().intValue();
                if (status.progress() >= 0) {
                    // Never let a supplier "0%" rewind the local submitted floor (or any later %).
                    task.markProgress(Math.max(local, status.progress()));
                } else if (local < 90) {
                    // No progress reported: creep forward so the UI shows movement without ever
                    // claiming completion.
                    task.markProgress(local + 5);
                }
                tasks.save(task);
            }
        }
    }

    /**
     * Downloads the result and registers it as an attachment of the task's owner.
     *
     * @param statusVideoUrl URL carried by the status payload; ignored when New API is on and this
     *                       row already has a {@code provider_task_id} (see {@link #resolveDownloadUrl})
     */
    private void finish(AsyncTaskEntity task, String statusVideoUrl) {
        CurrentUser owner = new CurrentUser(task.getUserId(), null, null);
        Long conversationId = readConversationId(task.getInputJson());
        // Through the relay by default; straight from the supplier when its row names a download
        // origin, which is the only way to get the bytes when the relay cannot proxy them.
        ProviderCredentials credentials;
        try {
            credentials = pollCredentials(task);
        } catch (ServiceUnavailableException | PaymentRequiredException exception) {
            // Same rule as polling the status: leave the job running and try again next cycle
            // rather than failing a video that has already been produced and paid for.
            log.warn("Unable to fetch video task {} yet: {}",
                    task.getTaskKey(), exception.getMessage());
            return;
        }
        String videoUrl = resolveDownloadUrl(task, statusVideoUrl, credentials);
        if (videoUrl == null || videoUrl.isBlank()) {
            markFailedUserFacing(task, "The video task succeeded without a result URL");
            tasks.save(task);
            return;
        }
        String downloadKey = downloadKey(credentials, videoUrl);
        // The origin the operator declared for this supplier vouches for its host, so a supplier on
        // an internal address still delivers; without it only the configured relay is exempt.
        String trustedOrigin = credentials == null ? null : credentials.videoResultBaseUrl();

        try (com.hub.backend.infra.llm.video.VideoUrlDownloader.DownloadedVideo downloaded =
                     videoUrlDownloader.download(videoUrl, downloadKey, trustedOrigin)) {
            String extension = extensionFor(downloaded.mimeType(), videoUrl);
            String mimeType = mimeTypeFor(extension, downloaded.mimeType());

            try (InputStream content = Files.newInputStream(downloaded.path())) {
                UploadedFileResponse stored = fileService.storeGenerated(
                        owner,
                        content,
                        downloaded.sizeBytes(),
                        extension,
                        mimeType,
                        "generated-video." + extension,
                        conversationId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("fileId", stored.id());
                result.put("videoUrl", stored.url());
                result.put("mimeType", stored.mimeType());
                task.markSucceeded(json(result), LocalDateTime.now());
            }
        } catch (LlmInvocationException exception) {
            markFailedUserFacing(task, exception.getMessage());
        } catch (java.io.IOException exception) {
            // 不把 IOException 原文（可能含本地路径）拼进 rawReason；describeAsync 虽会归一，
            // 但日志里也不需要路径细节——这里已有 taskKey，底层 stack 仍会打出来。
            log.warn("Unable to read generated video for task {}", task.getTaskKey(), exception);
            markFailedUserFacing(task, "The generated video could not be read");
        } catch (RuntimeException exception) {
            log.error("Unable to store the generated video for task {}", task.getTaskKey(), exception);
            markFailedUserFacing(task, "The generated video could not be stored");
        }
        tasks.save(task);
    }

    /**
     * Picks the URL used to fetch finished bytes.
     *
     * <p>With New API enabled, prefer
     * {@code LLM_BASE_URL + videos-path + /{provider_task_id}/content} and the caller's relay key.
     * The xAI status payload's relative {@code /v1/videos/{uuid}/content} names an upstream id that
     * is not fetchable on the relay; resolving it against a hard-coded supplier origin then using
     * the relay key produces HTTP 401. Supplier-direct download remains available only when the
     * status URL already lands on a host declared via {@code video_download_base_url}.</p>
     */
    private String resolveDownloadUrl(AsyncTaskEntity task,
                                      String statusVideoUrl,
                                      ProviderCredentials credentials) {
        if (newApiProperties.isEnabled()) {
            String providerTaskId = task.getProviderTaskId();
            if (providerTaskId != null && !providerTaskId.isBlank()) {
                String base = credentials != null && credentials.hasBaseUrl()
                        ? credentials.baseUrl()
                        : llmProperties.resolvedBaseUrl();
                String videosPath = credentials != null && credentials.hasVideosPath()
                        ? credentials.videosPath()
                        : llmProperties.getVideosPath();
                String relayUrl = ProviderVideoDownload.relayContentUrl(
                        base, videosPath, providerTaskId);
                if (relayUrl != null) {
                    return relayUrl;
                }
            }
        }
        if (statusVideoUrl != null && !statusVideoUrl.isBlank()
                && ProviderVideoDownload.usesProviderHost(credentials, statusVideoUrl)) {
            return statusVideoUrl;
        }
        if (!newApiProperties.isEnabled()) {
            return statusVideoUrl;
        }
        // New API on but no provider_task_id: do not fall back to a status URL that may have been
        // absolute-ised against a supplier host with the wrong key.
        return null;
    }

    private AsyncTaskEntity requireTask(String taskKey, Long userId) {
        return tasks.findByTaskKeyAndTaskTypeAndUserId(
                        taskKey, AsyncTaskEntity.TYPE_VIDEO_GENERATION, userId)
                .orElseThrow(() -> new NotFoundException(
                        "Video task '%s' does not exist".formatted(taskKey)));
    }

    private void fail(Long taskId, String reason) {
        tasks.findById(taskId).filter(task -> !task.isTerminal()).ifPresent(task -> {
            markFailedUserFacing(task, reason);
            tasks.save(task);
        });
    }

    /**
     * Persists a short Chinese reason for the client; the raw upstream/ops text stays in the log.
     */
    private void markFailedUserFacing(AsyncTaskEntity task, String rawReason) {
        log.warn("Video task {} failed: {}", task.getTaskKey(), rawReason);
        task.markFailed(UpstreamFailureText.describeAsync(rawReason), LocalDateTime.now());
    }

    private boolean isTimedOut(AsyncTaskEntity task) {
        LocalDateTime reference = task.getStartedAt() != null ? task.getStartedAt() : task.getCreatedAt();
        if (reference == null) {
            return false;
        }
        Duration limit = videoProperties.getTaskTimeout();
        return reference.plus(limit).isBefore(LocalDateTime.now());
    }

    /**
     * Key used to fetch the finished video.
     *
     * <p>With New API relay enabled, the content URL requires the same user relay token that
     * submitted the job — a stale {@code app.llm.video-download-api-key} must not override it.
     * That override is only consulted when New API is off (local static-key setups) and the
     * artifact authenticates differently from the submit call.</p>
     *
     * <p>A supplier that declares its own download origin is the exception: the URL then points at
     * that host, where the relay key is not accepted at all, so the supplier key declared on the
     * same row is used instead. {@link ProviderVideoDownload#usesProviderHost} checks the resolved
     * host rather than trusting the configuration blindly, so a supplier key is never sent
     * somewhere else.</p>
     */
    private String downloadKey(ProviderCredentials credentials, String videoUrl) {
        if (ProviderVideoDownload.usesProviderHost(credentials, videoUrl)) {
            return credentials.videoDownloadApiKey();
        }
        if (newApiProperties.isEnabled()) {
            return credentials == null ? null : credentials.apiKey();
        }
        String configured = llmProperties.getVideoDownloadApiKey();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return credentials == null ? null : credentials.apiKey();
    }

    /**
     * Credentials for submitting a job: the caller's New API relay key, same as chat and image.
     *
     * <p>New API task plugins own the upstream hop; this service only talks to the relay. Paths
     * come from {@code app.llm.videos-path} / {@code video-status-path}.</p>
     */
    private ProviderCredentials credentialsFor(CurrentUser user, Long modelId) {
        // 按被提交的模型所属分组取钥匙：视频模型常常只在独立分组（如「视频分组」「图片生成」）
        // 里有渠道，账号默认分组的钥匙会得到「该分组无可用渠道」。
        return relayCredentials.resolve(user, modelId);
    }

    /**
     * Credentials for polling a job that already exists: same key, without the balance check.
     *
     * <p>必须是**提交时那把钥匙**。视频任务在上游按令牌归属，用账号默认分组的钥匙去查会拿到
     * {@code 404 task_not_found}：任务明明在跑（上游 progress 一直在涨），我们这边却永远
     * 只看到提交时的进度，最后超时失败。所以这里和 {@link #credentialsFor} 一样按模型所属
     * 分组取钥匙，只是跳过余额检查。</p>
     */
    private ProviderCredentials pollCredentials(AsyncTaskEntity task) {
        // 提交时已经把 modelId 写进 input_json，直接用它去找分组钥匙；不要依赖「按目录重新解析
        // 模型」——那个会做可见性校验，模型没挂进策展分组时返回 null，于是又退回账号令牌。
        Long modelId = readLong(task.getInputJson(), "modelId");
        if (modelId == null) {
            ModelUseResolver.Resolved resolved = resolveForPoll(task);
            modelId = resolved == null ? null : resolved.id();
        }
        return ProviderVideoDownload.merge(
                relayCredentials.resolveKeyOnly(ownerOf(task), modelId),
                supplierDownloadCredentials(task));
    }

    /**
     * Supplier credentials carrying the origin a finished file is fetched from, or {@code null}
     * when that supplier declares no separate download origin.
     *
     * <p>Only the download side reads this: submitting and polling still go to the relay with the
     * caller's key, so usage stays billed to the caller.</p>
     */
    private ProviderCredentials supplierDownloadCredentials(AsyncTaskEntity task) {
        ModelUseResolver.Resolved resolved = resolveForPoll(task);
        if (resolved == null || resolved.id() == null) {
            return null;
        }
        return llmModels.findById(resolved.id())
                .map(LlmModelEntity::getProviderId)
                .map(providerCredentials::resolve)
                .orElse(null);
    }

    private CurrentUser ownerOf(AsyncTaskEntity task) {
        return new CurrentUser(task.getUserId(), readLong(task.getInputJson(), "siteId"), null);
    }

    /** Re-resolves the VIDEO model stored on the task so a restart can finish a pending submit. */
    private ModelUseResolver.Resolved resolveForPoll(AsyncTaskEntity task) {
        String modelKey = readString(task.getInputJson(), "model");
        if (modelKey == null) {
            return null;
        }
        Long siteId = readLong(task.getInputJson(), "siteId");
        try {
            return modelResolver.resolve(siteId, modelKey, modelKey, LlmModelEntity.TYPE_VIDEO);
        } catch (InvalidRequestException | NotFoundException exception) {
            return null;
        }
    }

    /**
     * Decides the generation mode.
     *
     * <p>An explicit mode is taken as given. Without one the evidence decides: multi reference
     * file ids mean reference-to-video, two frames mean first/last frame, one frame means image
     * to video, none means text to video. That keeps older clients working now that the catalogue
     * declares modes.</p>
     */
    private static String resolveMode(VideoGenerationRequest request, ModelUseResolver.Resolved resolved) {
        String requested = request.modeOrNull();
        if (requested != null) {
            return requested;
        }
        if (!request.referenceFileIdsOrEmpty().isEmpty()) {
            return MODE_REFERENCE_TO_VIDEO;
        }
        if (request.lastFrameFileId() != null) {
            return MODE_FIRST_LAST_FRAME;
        }
        if (request.referenceFileId() != null) {
            return MODE_IMAGE_TO_VIDEO;
        }
        return MODE_TEXT_TO_VIDEO;
    }

    /**
     * Mode-specific input rules so first-frame and reference-image semantics stay distinct.
     *
     * @throws InvalidRequestException when required images are missing or modes are mixed
     */
    static void requireModeInputs(String mode, VideoGenerationRequest request) {
        List<Long> refs = request.referenceFileIdsOrEmpty();
        boolean hasFirst = request.referenceFileId() != null;
        boolean hasLast = request.lastFrameFileId() != null;
        if (MODE_REFERENCE_TO_VIDEO.equals(mode)) {
            if (refs.isEmpty()) {
                throw new InvalidRequestException("参考图模式请至少上传一张参考图");
            }
            if (hasFirst || hasLast) {
                throw new InvalidRequestException(
                        "参考图模式不能同时带首帧或尾帧，请只选一种输入方式");
            }
            return;
        }
        if (!refs.isEmpty()) {
            throw new InvalidRequestException("多张参考图仅适用于参考图模式");
        }
        if (MODE_IMAGE_TO_VIDEO.equals(mode) && !hasFirst) {
            throw new InvalidRequestException("图生视频请先上传首帧图片");
        }
        if (MODE_FIRST_LAST_FRAME.equals(mode) && !hasFirst && !hasLast) {
            throw new InvalidRequestException("首尾帧模式请至少上传首帧或尾帧其中一张");
        }
    }

    /**
     * Rejects request values that fall outside a model's declared capabilities.
     *
     * <p>Each dimension is checked independently: a null declaration or an empty list means
     * "not declared for this field" and is left open, matching the historical mode gate. That
     * keeps models without a filled-in declaration usable until ops writes one in admin.</p>
     *
     * @throws InvalidRequestException when a request value is outside a declared capability range
     */
    private void requireCapabilitiesSupported(String mode,
                                              VideoGenerationRequest request,
                                              ModelUseResolver.Resolved resolved) {
        int attached = MODE_REFERENCE_TO_VIDEO.equals(mode)
                ? request.referenceFileIdsOrEmpty().size()
                : (request.referenceFileId() != null ? 1 : 0)
                        + (request.lastFrameFileId() != null ? 1 : 0);
        validateDeclaredCapabilities(
                resolved.requestKey(),
                publishedCapabilities(resolved),
                mode,
                request.aspectRatioOrNull(),
                request.resolutionOrNull(),
                request.duration(),
                attached);
    }

    /**
     * Package-visible for unit tests. {@code attachedImageCount} is the number of images that
     * count against {@code maxReferenceImages} (reference2video list size, or first+last frames).
     *
     * @throws InvalidRequestException when a declared capability range rejects the request value
     */
    static void validateDeclaredCapabilities(String modelKey,
                                             ModelCapabilities capabilities,
                                             String mode,
                                             String aspectRatio,
                                             String resolution,
                                             Integer duration,
                                             int attachedImageCount) {
        if (capabilities == null) {
            return;
        }
        requireDeclared(modelKey, "mode", mode, capabilities.modes());
        requireDeclared(modelKey, "aspectRatio", aspectRatio, capabilities.aspectRatios());
        requireDeclared(modelKey, "resolution", resolution, capabilities.resolutions());
        List<Integer> durations = capabilities.durations();
        if (duration != null && durations != null && !durations.isEmpty()
                && !capabilities.allowsDuration(duration)) {
            // 报错要说出真正合法的取值：只收档位的模型不能只说「4-15」，否则用户会以为 7 秒也行。
            if (ModelCapabilities.DURATION_MODE_SET.equalsIgnoreCase(
                    capabilities.durationMode() == null ? "" : capabilities.durationMode().strip())) {
                throw new InvalidRequestException(
                        "当前模型只支持 %s 秒，本次请求 %d 秒".formatted(
                                capabilities.durationOptions().stream()
                                        .map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(" / ")),
                                duration));
            }
            int minDuration = durations.stream().mapToInt(Integer::intValue).min().orElseThrow();
            int maxDuration = durations.stream().mapToInt(Integer::intValue).max().orElseThrow();
            throw new InvalidRequestException(
                    "当前模型支持的时长为 %d-%d 秒，本次请求 %d 秒"
                            .formatted(minDuration, maxDuration, duration));
        }
        if (capabilities.maxReferenceImages() > 0
                && attachedImageCount > capabilities.maxReferenceImages()) {
            throw new InvalidRequestException(
                    "当前模型最多支持 %d 张参考图，本次上传了 %d 张"
                            .formatted(capabilities.maxReferenceImages(),
                                    attachedImageCount));
        }
    }

    private static void requireDeclared(String modelKey,
                                        String field,
                                        String value,
                                        List<String> allowed) {
        if (value == null || allowed == null || allowed.isEmpty()) {
            return;
        }
        if (!allowed.contains(value)) {
            String fieldLabel = switch (field) {
                case "mode" -> "生成模式";
                case "aspectRatio" -> "画面比例";
                case "resolution" -> "分辨率";
                default -> field;
            };
            throw new InvalidRequestException(
                    "当前模型不支持该%s（%s），可选：%s"
                            .formatted(fieldLabel, value, String.join("、", allowed)));
        }
    }

    /**
     * The declaration the front end was shown for this model key.
     *
     * <p>It must be the same answer the catalogue published, not the member row's own JSON: when an
     * operator widens a group to 4–15 秒, the page offers 7 秒, and validating against the concrete
     * member (which may be a 10/15-only model) would reject a request the UI said was fine.</p>
     */
    private ModelCapabilities publishedCapabilities(ModelUseResolver.Resolved resolved) {
        ModelCapabilities published = resolved.requestKey() == null || resolved.id() == null
                ? null
                : capabilityPublisher.publishedFor(null, resolved.requestKey(), null);
        if (published != null) {
            return published;
        }
        return capabilitiesOf(resolved.id());
    }

    private ModelCapabilities capabilitiesOf(Long modelId) {
        if (modelId == null) {
            return null;
        }
        return llmModels.findById(modelId)
                .map(capabilityPublisher::capabilitiesOf)
                .orElse(null);
    }

    /**
     * Loads a user-owned attachment and encodes it as a {@code data:} URL for the video gateway.
     *
     * <p>Ownership was already checked at submit time; {@link FileService#download} checks again so
     * a deleted file fails here instead of sending a dead path upstream.</p>
     */
    private String referenceImageDataUrl(Long fileId, CurrentUser currentUser) {
        if (fileId == null) {
            return null;
        }
        DownloadedFile downloaded = fileService.download(fileId, currentUser);
        try (InputStream input = downloaded.resource().getInputStream()) {
            return toImageDataUrl(downloaded.file().getMimeType(), input.readAllBytes());
        } catch (IOException exception) {
            throw new LlmInvocationException(
                    "Unable to read reference image %d for video generation".formatted(fileId),
                    exception);
        }
    }

    /**
     * Builds a data URL the relay can consume without calling back into this host.
     *
     * <p>Package-visible for unit tests.</p>
     */
    static String toImageDataUrl(String mimeType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("参考图内容为空，请重新上传");
        }
        String mime = mimeType == null || mimeType.isBlank()
                ? "image/png"
                : mimeType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/")) {
            throw new InvalidRequestException("参考文件必须是图片");
        }
        return "data:%s;base64,%s".formatted(mime, Base64.getEncoder().encodeToString(content));
    }

    private VideoTaskResponse toResponse(AsyncTaskEntity task) {
        Map<String, Object> input = parse(task.getInputJson());
        Map<String, Object> result = parse(task.getResultJson());
        // 历史行可能仍是英文原文：读出时再归类一次，保证接口始终是短中文。
        String errorMessage = task.getErrorMessage() == null || task.getErrorMessage().isBlank()
                ? null
                : UpstreamFailureText.describeAsync(task.getErrorMessage());
        Long referenceFileId = asLong(input.get("referenceFileId"));
        Long lastFrameFileId = asLong(input.get("lastFrameFileId"));
        List<Long> referenceFileIds = asLongList(input.get("referenceFileIds"));
        List<String> referenceUrls = referenceFileIds.stream()
                .map(VideoGenerationService::fileContentUrl)
                .toList();
        return new VideoTaskResponse(
                task.getTaskKey(),
                VideoTaskResponse.externalStatus(task.getStatus()),
                task.getProgress() == null ? 0 : task.getProgress().intValue(),
                asString(input.get("model")),
                asString(input.get("prompt")),
                asString(input.get("mode")),
                asString(input.get("aspectRatio")),
                asString(input.get("resolution")),
                asInteger(input.get("duration")),
                referenceFileId,
                fileContentUrl(referenceFileId),
                lastFrameFileId,
                fileContentUrl(lastFrameFileId),
                referenceFileIds,
                referenceUrls,
                asString(result.get("videoUrl")),
                asLong(result.get("fileId")),
                errorMessage,
                task.getCreatedAt(),
                task.getCompletedAt());
    }

    /** Same relative content URL shape as {@code UploadedFileResponse}. */
    private static String fileContentUrl(Long fileId) {
        return fileId == null || fileId <= 0 ? null : "/api/v1/files/%d/content".formatted(fileId);
    }

    private VideoGenerationRequest requestFromJson(String inputJson) {
        Map<String, Object> input = parse(inputJson);
        String prompt = asString(input.get("prompt"));
        if (prompt == null) {
            return null;
        }
        return new VideoGenerationRequest(
                prompt,
                asString(input.get("model")),
                asString(input.get("aspectRatio")),
                asString(input.get("resolution")),
                input.get("duration") == null ? null : ((Number) input.get("duration")).intValue(),
                asLong(input.get("referenceFileId")),
                asLong(input.get("lastFrameFileId")),
                asLongList(input.get("referenceFileIds")),
                asString(input.get("mode")),
                asLong(input.get("conversationId")));
    }

    private Long readConversationId(String inputJson) {
        return asLong(parse(inputJson).get("conversationId"));
    }

    private String readString(String inputJson, String key) {
        return asString(parse(inputJson).get(key));
    }

    private Long readLong(String inputJson, String key) {
        return asLong(parse(inputJson).get(key));
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> value = objectMapper.convertValue(node, Map.class);
            return value;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialise a video task payload", exception);
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /** 存进 input_json 的附件 ID 列表读回来时是 {@code List<?>}；逐个按数字解析。 */
    private static List<Long> asLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(VideoGenerationService::asLong)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private static String extensionFor(String mimeType, String url) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase();
        if (normalized.contains("webm")) {
            return "webm";
        }
        if (normalized.contains("mp4") || normalized.contains("quicktime")) {
            return "mp4";
        }
        String fromUrl = com.hub.backend.infra.llm.video.HttpVideoUrlDownloader.extensionOf(url);
        if (fromUrl != null && (fromUrl.equals("mp4") || fromUrl.equals("webm"))) {
            return fromUrl;
        }
        return DEFAULT_EXTENSION;
    }

    private static String mimeTypeFor(String extension, String declared) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        return "webm".equals(extension) ? "video/webm" : DEFAULT_MIME;
    }

    /** Exposed for the poller: the newest timestamp a task may carry and still be polled. */
    LocalDateTime staleBefore() {
        return LocalDateTime.now().minus(videoProperties.getPollInterval());
    }

    Optional<AsyncTaskEntity> findById(Long id) {
        return tasks.findById(id);
    }
}
