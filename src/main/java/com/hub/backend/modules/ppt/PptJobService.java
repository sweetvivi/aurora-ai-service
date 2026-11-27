package com.hub.backend.modules.ppt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.pptmaster.DeckWorkspaceResolver;
import com.hub.backend.infra.pptmaster.PptMasterClient;
import com.hub.backend.infra.pptmaster.PptMasterClient.PptMasterGenerateRequest;
import com.hub.backend.infra.pptmaster.PptMasterClient.PptMasterGenerationResult;
import com.hub.backend.infra.pptmaster.PptMasterClient.ResolvedCredentials;
import com.hub.backend.infra.pptmaster.PptProcessRegistry;
import com.hub.backend.infra.pptmaster.agent.AgentBriefBuilder;
import com.hub.backend.infra.pptmaster.agent.AgentGenerateRequest;
import com.hub.backend.infra.queue.TaskMessage;
import com.hub.backend.infra.queue.TaskQueue;
import com.hub.backend.infra.queue.TaskQueues;
import com.hub.backend.infra.storage.FileStorage;
import com.hub.backend.infra.storage.StoredObject;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.ppt.beauty.BeautyOrchestrator;
import com.hub.backend.modules.ppt.dto.PptDtos.CreateJobRequest;
import com.hub.backend.modules.ppt.dto.PptDtos.CreateJobResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.EstimateResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.JobDetailResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.JobSummaryResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.OptimizePromptRequest;
import com.hub.backend.modules.ppt.dto.PptDtos.OptimizePromptResponse;
import com.hub.backend.modules.ppt.entity.PptDeckTemplateEntity;
import com.hub.backend.modules.ppt.entity.PptJobEntity;
import com.hub.backend.modules.ppt.repository.PptJobRepository;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PptJobService {

    private static final Logger log = LoggerFactory.getLogger(PptJobService.class);
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_FILE_TEXT_CHARS = 20_000;
    /** 与 {@code ppt_jobs.error_message} 的列长一致；实体 markFailed 也是按这个口径截断的。 */
    private static final int MAX_ERROR_CHARS = 2048;

    private final PptJobRepository jobRepository;
    private final PptMasterClient pptMasterClient;
    private final UserRelayCredentialResolver relayCredentials;
    private final FileStorage fileStorage;
    private final FileService fileService;
    private final PptEstimateService estimateService;
    private final PptProperties properties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor pptJobExecutor;
    private final PptJobCapacity capacity;
    private final PptProcessRegistry processRegistry;
    private final BeautyOrchestrator beautyOrchestrator;
    private final PptDeckTemplateService deckTemplateService;
    private final DeckWorkspaceResolver deckWorkspaceResolver;
    private final UserAccountRepository userAccounts;
    private final TaskQueue taskQueue;
    /** 本进程的租约所有者标识。重启后换新的，旧租约只能等自然过期。 */
    private final String instanceId;
    private final Duration leaseTtl;
    private final Duration queuedStaleAfter;
    private final Duration queuedMaxAge;
    private final int recoveryBatchSize;

    public PptJobService(PptJobRepository jobRepository,
                         PptMasterClient pptMasterClient,
                         UserRelayCredentialResolver relayCredentials,
                         FileStorage fileStorage,
                         FileService fileService,
                         PptEstimateService estimateService,
                         PptProperties properties,
                         LlmProperties llmProperties,
                         ObjectMapper objectMapper,
                         @Qualifier("pptJobExecutor") AsyncTaskExecutor pptJobExecutor,
                         PptJobCapacity capacity,
                         PptProcessRegistry processRegistry,
                         BeautyOrchestrator beautyOrchestrator,
                         PptDeckTemplateService deckTemplateService,
                         DeckWorkspaceResolver deckWorkspaceResolver,
                         UserAccountRepository userAccounts,
                         TaskQueue taskQueue) {
        this.jobRepository = jobRepository;
        this.pptMasterClient = pptMasterClient;
        this.relayCredentials = relayCredentials;
        this.fileStorage = fileStorage;
        this.fileService = fileService;
        this.estimateService = estimateService;
        this.properties = properties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.pptJobExecutor = pptJobExecutor;
        this.capacity = capacity;
        this.processRegistry = processRegistry;
        this.beautyOrchestrator = beautyOrchestrator;
        this.deckTemplateService = deckTemplateService;
        this.deckWorkspaceResolver = deckWorkspaceResolver;
        this.userAccounts = userAccounts;
        this.taskQueue = taskQueue;
        this.instanceId = "ppt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PptProperties.PptMaster cfg = properties.getPptMaster();
        this.leaseTtl = cfg.getLeaseTtl();
        this.queuedStaleAfter = cfg.getQueuedStaleAfter();
        this.queuedMaxAge = cfg.getQueuedMaxAge();
        this.recoveryBatchSize = Math.max(1, cfg.getRecoveryBatchSize());
    }

    @Transactional
    public CreateJobResponse create(CurrentUser user, CreateJobRequest request) {
        if (request == null) {
            throw new InvalidRequestException("请求体不能为空");
        }
        boolean hasTopic = StringUtils.hasText(request.topic());
        boolean hasSource = StringUtils.hasText(request.sourceText());
        boolean hasFiles = request.fileIds() != null && !request.fileIds().isEmpty();
        if (!hasTopic && !hasSource && !hasFiles) {
            throw new InvalidRequestException("请至少提供主题、粘贴文本或参考文件之一");
        }

        String generateMode = normalizeGenerateMode(request.generateMode());
        String deckTemplateId = null;
        if ("template".equals(generateMode)) {
            if (!StringUtils.hasText(request.deckTemplateId())) {
                throw new InvalidRequestException("模板库生成必须选择 deckTemplateId");
            }
            PptDeckTemplateEntity deck = deckTemplateService.requirePublished(request.deckTemplateId());
            deckTemplateId = deck.getCode();
        }

        String templateId = StringUtils.hasText(request.templateId())
                ? request.templateId().trim()
                : ("template".equals(generateMode) ? "blue-business-text" : "auto");

        boolean autoImg = Boolean.TRUE.equals(request.autoImg());
        boolean webSearch = Boolean.TRUE.equals(request.webSearch());
        String imgDensity = PptMasterClient.normalizeDensity(request.imgDensity());
        EstimateResponse estimate = estimateService.estimate(
                request.pageRange(), autoImg, webSearch, imgDensity);

        // 计数与插入必须在同一段临界区里，而且锁要持有到事务提交 —— 否则并发的第二个请求数不到
        // 第一个还没提交的那一行，两个都会通过准入。见 PptJobCapacity。
        //
        // deckTemplateId 在上面按分支赋过值，不是 effectively final，lambda 里要用它的副本。
        final String admittedDeckTemplateId = deckTemplateId;
        return capacity.withAdmission(() -> {
            String jobId = "ppt_job_" + UUID.randomUUID().toString().replace("-", "");
            String title = PptRequestMapper.safeTitle(request.topic(), "未命名演示文稿");
            PptJobEntity job = new PptJobEntity(jobId, user.userId(), title);
            job.setTopic(trimToNull(request.topic()));
            job.setSourceText(trimToNull(request.sourceText()));
            job.setFileIdsJson(writeJson(request.fileIds() == null ? List.of() : request.fileIds()));
            job.setLang(trimToNull(request.lang()));
            job.setPageRange(trimToNull(request.pageRange()));
            job.setDetail(trimToNull(request.detail()));
            job.setScene(trimToNull(request.scene()));
            job.setTone(trimToNull(request.tone()));
            job.setTextModelId(trimToNull(request.textModelId()));
            job.setImageModelId(trimToNull(request.imageModelId()));
            job.setAutoImg(autoImg);
            job.setImgDensity(imgDensity);
            job.setWebSearch(webSearch);
            job.setTemplateId(templateId);
            job.setGenerateMode(generateMode);
            job.setDeckTemplateId(admittedDeckTemplateId);
            job.setAspectRatio(PptMasterClient.normalizeAspectRatio(request.aspectRatio()));
            job.setPageCount(PptRequestMapper.resolveSlideCount(request.pageRange()));
            job.setEstimatedPoints(estimate.estimatedPoints());
            job.markProgress(PptJobEntity.STATUS_QUEUED, 0, "排队中");
            jobRepository.save(job);

            scheduleAfterCommit(jobId, user);
            return new CreateJobResponse(jobId, PptJobEntity.STATUS_QUEUED, estimate.estimatedPoints());
        });
    }

    private void scheduleAfterCommit(String jobId, CurrentUser user) {
        Runnable start = () -> submit(jobId, user);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            start.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                start.run();
            }
        });
    }

    /**
     * 领取任务再提交给线程池。
     *
     * <p>顺序很关键：先 {@code queued → parsing} 落库，再 execute。如果先提交后领取，一个正在
     * 线程池队列里排队的任务在库里仍然显示 queued，恢复扫描会把它当成「提交丢了」再提交一遍，
     * 线程池越堵重复提交越多。领取之后，库里只有一个 worker 能拿到它。</p>
     *
     * <p>线程池拒收（队列满）时把领取还回去，任务继续留在 queued，由下一轮恢复扫描重试 ——
     * 比让请求线程抛异常、任务停在半路要好。</p>
     */
    private boolean submit(String jobId, CurrentUser user) {
        if (!claim(jobId)) {
            log.debug("PPT job {} is not claimable (already claimed or cancelled)", jobId);
            return false;
        }
        try {
            pptJobExecutor.execute(() -> runClaimed(jobId, user));
            return true;
        } catch (TaskRejectedException e) {
            jobRepository.releaseClaim(jobId, instanceId, "排队中", LocalDateTime.now());
            log.warn("PPT executor is saturated; job {} is back in the queue for the recovery sweep", jobId);
            return false;
        }
    }

    private boolean claim(String jobId) {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.claimForExecution(
                jobId, instanceId, now.plus(leaseTtl), "生成中", now) == 1;
    }

    private void runClaimed(String jobId, CurrentUser user) {
        try {
            runJob(jobId, user);
        } catch (RuntimeException e) {
            // runJob 内部已经兜住业务异常；这里接的是它之外的问题（例如进度写入炸了），
            // 不接的话线程池只会打一行日志，任务就永远停在中间状态。
            log.error("PPT job crashed jobId={}", jobId, e);
            fail(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 恢复扫描第一步：把「租约过期但仍在跑」的任务判失败。
     *
     * @return 被判死的任务数
     */
    public int failInterruptedJobs() {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.failStuck(
                PptJobEntity.STARTED_STATUSES,
                "任务已中断（服务重启或进程退出），请重新发起生成",
                now,
                now.minus(leaseTtl));
    }

    /**
     * 恢复扫描第二步：把早已过期、还停在队列里的任务判失败。
     *
     * <p>修复重启丢任务之后，线上那批历史行会第一次被扫到。超过 {@code queuedMaxAge} 的不再复活，
     * 否则部署当天会把几天前的任务一起跑起来。</p>
     *
     * @return 被判死的任务数
     */
    public int failAbandonedQueuedJobs() {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.failAbandonedQueued(
                now.minus(queuedMaxAge), "任务已过期（长时间未开始生成），请重新发起", now);
    }

    /**
     * 恢复扫描第三步：重新提交还停在 queued 的任务。
     *
     * <p>只处理 {@code queuedStaleAfter ~ queuedMaxAge} 之间的行：太新的可能只是在排队，
     * 太旧的由上一步判失败。</p>
     *
     * @return 本轮真正提交出去的任务数
     */
    public int resumeStaleQueuedJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<PptJobEntity> stale = jobRepository.findStaleQueued(
                now.minus(queuedStaleAfter), now.minus(queuedMaxAge), PageRequest.of(0, recoveryBatchSize));
        int submitted = 0;
        for (PptJobEntity job : stale) {
            if (!capacity.hasRoom()) {
                log.debug("PPT capacity is full; {} stranded job(s) wait for the next sweep", stale.size() - submitted);
                break;
            }
            CurrentUser user = userAccounts.findById(job.getUserId())
                    .map(CurrentUser::of)
                    .orElse(null);
            if (user == null) {
                fail(job.getJobId(), "任务所属账号不存在");
                continue;
            }
            if (submit(job.getJobId(), user)) {
                submitted++;
            }
        }
        return submitted;
    }

    @Transactional(readOnly = true)
    public JobDetailResponse get(CurrentUser user, String jobId) {
        return toDetail(requireOwned(user, jobId));
    }

    @Transactional
    public void cancel(CurrentUser user, String jobId) {
        PptJobEntity job = requireOwned(user, jobId);
        if (PptJobEntity.STATUS_SUCCEEDED.equals(job.getStatus())
                || PptJobEntity.STATUS_FAILED.equals(job.getStatus())
                || PptJobEntity.STATUS_CANCELLED.equals(job.getStatus())) {
            return;
        }
        processRegistry.cancel(jobId);
        job.markCancelled();
        jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public PageResponse<JobSummaryResponse> list(CurrentUser user, String status, Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(p, s);
        Page<PptJobEntity> result;
        if (StringUtils.hasText(status)) {
            result = jobRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.userId(), status.trim(), pageable);
        } else {
            result = jobRepository.findByUserIdOrderByCreatedAtDesc(user.userId(), pageable);
        }
        List<JobSummaryResponse> items = result.getContent().stream().map(this::toSummary).toList();
        return PageResponse.of(items, result.getTotalElements(), p, s);
    }

    /**
     * 删除任务与它的产物。
     *
     * <p>以前是在事务里直接删盘、失败只记一行 warn：删盘失败就是永久孤儿；更糟的是删盘成功后
     * 事务回滚，行还在、文件没了，下载直接 404。现在改成投一条 {@code storage.delete} 消息
     * （与删行同事务），由队列重试到底。</p>
     */
    @Transactional
    public void delete(CurrentUser user, String jobId) {
        PptJobEntity job = requireOwned(user, jobId);
        if (StringUtils.hasText(job.getStorageKey())) {
            taskQueue.publish(TaskMessage.of(TaskQueues.STORAGE_DELETE, job.getStorageKey()));
        }
        jobRepository.delete(job);
    }

    @Transactional(readOnly = true)
    public DownloadedPpt download(CurrentUser user, String jobId) {
        PptJobEntity job = requireOwned(user, jobId);
        if (!PptJobEntity.STATUS_SUCCEEDED.equals(job.getStatus()) || !StringUtils.hasText(job.getStorageKey())) {
            throw new InvalidRequestException("任务尚未成功生成，无法下载");
        }
        Resource resource = fileStorage.load(job.getStorageKey());
        String fileName = StringUtils.hasText(job.getFileName()) ? job.getFileName() : job.getJobId() + ".pptx";
        return new DownloadedPpt(fileName, resource);
    }

    public OptimizePromptResponse optimizePrompt(OptimizePromptRequest request) {
        String topic = request.topic().trim();
        String scene = StringUtils.hasText(request.scene()) ? request.scene().trim() : "通用";
        String lang = PptRequestMapper.mapLanguage(request.lang());
        String optimized = "请围绕「%s」生成结构清晰、适合「%s」场景的专业演示文稿（输出语言：%s），突出核心观点、数据支撑与行动建议。"
                .formatted(topic, scene, lang);
        return new OptimizePromptResponse(optimized);
    }

    private void runJob(String jobId, CurrentUser user) {
        long jobStartedAt = System.nanoTime();
        PptJobEntity job = waitForJob(jobId);
        if (job == null) {
            log.warn("PPT job missing after commit wait jobId={}", jobId);
            return;
        }
        try {
            if (job.isCancelled() || isFinished(job.getStatus())) {
                // 已取消，或者这一行在排队期间被恢复扫描判死了：不要再花一次上游调用。
                log.info("PPT job {} is already {}, worker stops", jobId, job.getStatus());
                return;
            }
            updateProgress(jobId, PptJobEntity.STATUS_PARSING, 8, "整理输入素材");
            String fileText = readReferenceFiles(user, readFileIds(job.getFileIdsJson()));
            if (isCancelled(jobId)) {
                return;
            }

            String content = PptRequestMapper.buildContent(job.getTopic(), job.getSourceText(), fileText);
            if (!StringUtils.hasText(content)) {
                fail(jobId, "没有可用于生成的内容");
                return;
            }

            Integer slides = PptRequestMapper.resolveSlideCount(job.getPageRange());
            String apiKey = resolveRelayApiKey(user);
            String generateMode = normalizeGenerateMode(job.getGenerateMode());

            PptMasterGenerateRequest generateRequest = buildBaseRequest(
                    job, user, content, slides, apiKey, generateMode);

            if ("template".equals(generateMode)) {
                PptDeckTemplateEntity deck = deckTemplateService.requirePublished(job.getDeckTemplateId());
                Path workspace = deckWorkspaceResolver.resolve(deck.getCode());
                generateRequest = new PptMasterGenerateRequest(
                        generateRequest.jobId(), generateRequest.title(), generateRequest.content(),
                        generateRequest.nSlides(), generateRequest.language(), generateRequest.scene(),
                        generateRequest.tone(), generateRequest.detail(), generateRequest.model(),
                        generateRequest.apiKey(), generateRequest.imageModel(), generateRequest.autoImg(),
                        generateRequest.imgDensity(),
                        StringUtils.hasText(deck.getThemeTemplateId()) ? deck.getThemeTemplateId() : generateRequest.templateId(),
                        generateRequest.aspectRatio(), generateRequest.siteId(),
                        "template", deck.getCode(), deck.getName(), deck.getStyleBlurb(), deck.getSpecJson(),
                        workspace.toString(), null);
                updateProgress(jobId, PptJobEntity.STATUS_RENDERING, 25, "模板库：Claude Agent 填槽生成中");
            } else {
                // 精品：beauty 01–04 → Agent Stage05
                updateProgress(jobId, PptJobEntity.STATUS_RENDERING, 12, "精品编排准备中");
                ResolvedCredentials creds = pptMasterClient.resolveCredentials(generateRequest);
                Path workDir = Path.of(properties.getPptMaster().getWorkDir() == null
                                ? "./data/ppt-master-jobs" : properties.getPptMaster().getWorkDir())
                        .toAbsolutePath().normalize();
                Path jobDir = workDir.resolve(AgentBriefBuilder.sanitizeJobId(jobId));
                String chatBase = creds.text().credentials() != null
                        ? nullToEmpty(creds.text().credentials().baseUrl()) : "";
                if (!StringUtils.hasText(chatBase)) {
                    chatBase = StringUtils.hasText(properties.getPptMaster().getAgentBaseUrl())
                            ? properties.getPptMaster().getAgentBaseUrl().trim()
                            : nullToEmpty(llmProperties.resolvedBaseUrl());
                }
                String chatKey = creds.text().credentials() != null
                        ? nullToEmpty(creds.text().credentials().apiKey()) : "";
                if (!StringUtils.hasText(chatKey)) {
                    chatKey = nullToEmpty(properties.getPptMaster().getAgentAuthToken());
                }
                BeautyOrchestrator.ChatConfig chat = new BeautyOrchestrator.ChatConfig(
                        chatBase,
                        chatKey,
                        nullToEmpty(creds.text().upstreamModel()));
                AgentGenerateRequest.ImageLlmConfig imageLlm = null;
                if (job.isAutoImg() && creds.image().credentials() != null
                        && creds.image().credentials().hasApiKey()) {
                    imageLlm = new AgentGenerateRequest.ImageLlmConfig(
                            nullToEmpty(creds.image().credentials().baseUrl()),
                            creds.image().credentials().apiKey(),
                            nullToEmpty(creds.image().upstreamModel()),
                            null);
                }
                BeautyOrchestrator.Bundle bundle = beautyOrchestrator.run(new BeautyOrchestrator.Options(
                        properties.getPptMaster().getRepoRoot(),
                        jobDir.toString(),
                        job.getTopic(),
                        content,
                        slides == null ? 8 : slides,
                        PptRequestMapper.mapLanguage(job.getLang()),
                        job.getScene(),
                        PptRequestMapper.mapTone(job.getTone()),
                        job.getDetail(),
                        job.getAspectRatio(),
                        job.isAutoImg(),
                        chat,
                        imageLlm,
                        (p, msg) -> updateProgress(jobId, PptJobEntity.STATUS_RENDERING, p, msg)
                ));
                if (isCancelled(jobId)) {
                    return;
                }
                generateRequest = generateRequest
                        .withGenerateMode("beauty")
                        .withBeautyStageDir(bundle.stageDir().toString())
                        .withNSlides(bundle.pageCount() > 0 ? bundle.pageCount() : slides);
                updateProgress(jobId, PptJobEntity.STATUS_RENDERING, 65, "精品：Claude Agent 版面实现中");
            }

            log.info("PPT generation submitted jobId={} mode={} engine=agent slides={} autoImg={}",
                    jobId, generateRequest.generateMode(), generateRequest.nSlides(), job.isAutoImg());
            long engineStartedAt = System.nanoTime();
            PptMasterGenerationResult result = pptMasterClient.generate(generateRequest);
            int pageCount = result.slides() > 0
                    ? result.slides()
                    : (generateRequest.nSlides() == null ? 0 : generateRequest.nSlides());
            String enginePath = result.pptxPath().toString();
            String presentationId = result.projectPath();
            byte[] bytes = pptMasterClient.readPptx(result.pptxPath());
            String fileName = fileNameFromPath(enginePath, job.getTitle());

            log.info("PPT engine generation completed jobId={} engine=claude-agent elapsedMs={} slides={}",
                    jobId, (System.nanoTime() - engineStartedAt) / 1_000_000, pageCount);
            if (isCancelled(jobId)) {
                return;
            }

            updateProgress(jobId, PptJobEntity.STATUS_RENDERING, 90, "下载并保存 pptx");
            StoredObject stored;
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                stored = fileStorage.store(in, "pptx");
            }

            succeed(jobId, stored.storageKey(), fileName, presentationId, enginePath, pageCount,
                    job.getEstimatedPoints());
        } catch (Exception e) {
            log.warn("PPT job failed jobId={} elapsedMs={} errorType={} error={}", jobId,
                    (System.nanoTime() - jobStartedAt) / 1_000_000, e.getClass().getSimpleName(), e.getMessage());
            fail(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private PptMasterGenerateRequest buildBaseRequest(PptJobEntity job,
                                                      CurrentUser user,
                                                      String content,
                                                      Integer slides,
                                                      String apiKey,
                                                      String generateMode) {
        return new PptMasterGenerateRequest(
                job.getJobId(),
                job.getTitle(),
                content,
                slides,
                PptRequestMapper.mapLanguage(job.getLang()),
                job.getScene(),
                PptRequestMapper.mapTone(job.getTone()),
                job.getDetail(),
                job.getTextModelId(),
                apiKey,
                job.getImageModelId(),
                job.isAutoImg(),
                job.getImgDensity(),
                job.getTemplateId(),
                job.getAspectRatio(),
                user.siteId(),
                generateMode,
                job.getDeckTemplateId(),
                null, null, null, null, null);
    }

    private static String normalizeGenerateMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "agent";
        }
        String m = raw.trim().toLowerCase(Locale.ROOT);
        if ("template".equals(m)) {
            return "template";
        }
        if ("agent".equals(m) || "beauty".equals(m) || "beauty_free".equals(m)) {
            return "agent";
        }
        throw new InvalidRequestException("generateMode 仅支持 agent 或 template");
    }

    private String resolveRelayApiKey(CurrentUser user) {
        try {
            return relayCredentials.resolve(user).apiKey();
        } catch (Exception e) {
            log.warn("PPT relay credential unavailable userId={}: {} (offline layouts allowed={})",
                    user.userId(), e.getMessage(), properties.getPptMaster().isAllowOffline());
            return null;
        }
    }

    private String readReferenceFiles(CurrentUser user, List<String> fileIds) {
        if (fileIds.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String rawId : fileIds) {
            Long id = parseFileId(rawId);
            if (id == null) {
                continue;
            }
            try {
                DownloadedFile downloaded = fileService.download(id, user);
                String name = downloaded.file().getOriginalName();
                String ext = downloaded.file().getExtension() == null
                        ? ""
                        : downloaded.file().getExtension().toLowerCase(Locale.ROOT);
                if (!(ext.equals("txt") || ext.equals("md") || ext.equals("csv") || ext.equals("json"))) {
                    sb.append("（已附加文件：").append(name).append("，一期仅文本类文件会展开内容）\n");
                    continue;
                }
                try (InputStream in = downloaded.resource().getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (text.length() > MAX_FILE_TEXT_CHARS) {
                        text = text.substring(0, MAX_FILE_TEXT_CHARS) + "\n…(截断)";
                    }
                    sb.append("文件「").append(name).append("」内容：\n").append(text).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("读取参考文件失败 fileId={}: {}", rawId, e.getMessage());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static Long parseFileId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("file_")) {
            value = value.substring("file_".length());
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 推进进度：定向条件更新，不再「读实体 → 改字段 → save 整行」。
     *
     * <p>整行 save 会拿 worker 手里的旧快照覆盖掉用户刚点的「取消」（快照里 {@code cancelled=false}），
     * 任务于是继续跑。条件写进 where 之后，取消一旦提交，这类写入只会命中 0 行。</p>
     *
     * <p>这条 UPDATE 同时续租：能写进进度，就说明执行者还活着，恢复扫描因此不会把它判成中断。</p>
     */
    private void updateProgress(String jobId, String status, int progress, String message) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jobRepository.applyProgress(
                jobId, status, progress, message, now.plus(leaseTtl), now);
        if (updated == 0) {
            // 已取消或已到终态：进度写不进去是正常结果，调用方自己会再判断要不要继续。
            log.debug("PPT job {} is already cancelled or finished; progress '{}' dropped", jobId, status);
        }
    }

    private void succeed(String jobId, String storageKey, String fileName,
                         String presentationId, String enginePath, Integer pageCount, int pointsCost) {
        Integer pages = pageCount != null && pageCount > 0 ? pageCount : null;
        int updated = jobRepository.markSucceededIfActive(
                jobId, storageKey, fileName, pages,
                // estimatedPoints 建单后不再变化，直接传进来，避免为取值再读一次实体。
                pointsCost, presentationId, enginePath, LocalDateTime.now());
        if (updated == 0) {
            // 取消先落地了：条件更新没命中，这份产物没有主人，删掉，别留孤儿文件。
            try {
                fileStorage.delete(storageKey);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private void fail(String jobId, String error) {
        String described = UpstreamFailureText.describeAsync(error);
        // 与实体 markFailed 的截断口径一致（error_message 列长 2048）。
        String message = described == null || described.length() <= MAX_ERROR_CHARS
                ? described
                : described.substring(0, MAX_ERROR_CHARS);
        int updated = jobRepository.markFailedIfActive(jobId, message, LocalDateTime.now());
        if (updated > 0) {
            log.warn("PPT job {} failed: {}", jobId, error);
        }
    }

    private boolean isCancelled(String jobId) {
        return jobRepository.findById(jobId).map(PptJobEntity::isCancelled).orElse(true);
    }

    private static boolean isFinished(String status) {
        return PptJobEntity.STATUS_SUCCEEDED.equals(status)
                || PptJobEntity.STATUS_FAILED.equals(status)
                || PptJobEntity.STATUS_CANCELLED.equals(status);
    }

    private PptJobEntity waitForJob(String jobId) {
        for (int i = 0; i < 10; i++) {
            PptJobEntity job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                return job;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private PptJobEntity requireOwned(CurrentUser user, String jobId) {
        return jobRepository.findByJobIdAndUserId(jobId, user.userId())
                .orElseThrow(() -> new NotFoundException("PPT 任务不存在"));
    }

    private JobDetailResponse toDetail(PptJobEntity job) {
        String downloadUrl = PptJobEntity.STATUS_SUCCEEDED.equals(job.getStatus())
                ? "/api/v1/ppt/jobs/" + job.getJobId() + "/download"
                : null;
        return new JobDetailResponse(
                job.getJobId(),
                job.getStatus(),
                job.getProgress(),
                job.getMessage(),
                job.getTitle(),
                job.getPageCount(),
                job.getPointsCost(),
                readStringList(job.getPreviewUrlsJson()),
                downloadUrl,
                job.getErrorMessage() == null || job.getErrorMessage().isBlank()
                        ? null
                        : UpstreamFailureText.describeAsync(job.getErrorMessage()),
                job.getCreatedAt(),
                job.getFinishedAt(),
                job.getGenerateMode(),
                job.getDeckTemplateId(),
                job.getStage(),
                job.getProgressMsg());
    }

    private JobSummaryResponse toSummary(PptJobEntity job) {
        List<String> previews = readStringList(job.getPreviewUrlsJson());
        String cover = previews.isEmpty() ? null : previews.get(0);
        return new JobSummaryResponse(
                job.getJobId(),
                job.getStatus(),
                job.getTitle(),
                job.getPageCount(),
                cover,
                job.getCreatedAt(),
                job.getFinishedAt(),
                job.getGenerateMode(),
                job.getDeckTemplateId());
    }

    private List<String> readFileIds(String json) {
        return readStringList(json);
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String fileNameFromPath(String path, String title) {
        if (StringUtils.hasText(path)) {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        String base = StringUtils.hasText(title) ? title.trim() : "presentation";
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + ".pptx";
    }

    public record DownloadedPpt(String fileName, Resource resource) {
    }
}
