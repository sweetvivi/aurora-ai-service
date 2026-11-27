package com.hub.backend.modules.thesis;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.thesis.dto.ThesisDocumentResponse;
import com.hub.backend.modules.thesis.dto.ThesisDocumentSummaryResponse;
import com.hub.backend.modules.thesis.dto.ThesisFullTextRequest;
import com.hub.backend.modules.thesis.dto.ThesisFullTextResponse;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import com.hub.backend.modules.thesis.dto.ThesisOutlineRequest;
import com.hub.backend.modules.thesis.dto.ThesisOutlineUpdateRequest;
import com.hub.backend.modules.thesis.entity.ThesisDocumentEntity;
import com.hub.backend.modules.thesis.repository.ThesisDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Thesis drafts of the current account.
 *
 * <p>Every read is scoped by {@code userId}: a draft belonging to somebody else is reported as
 * {@code 404}, never {@code 403}, so the API does not leak the existence of other accounts' work —
 * the same rule the conversation module follows.</p>
 *
 * <p>Outline generation is free and does not create a draft: it returns a tree the user can play
 * with before deciding to save. Only {@code POST /documents} persists, which keeps "free outline"
 * from filling the table with abandoned drafts.</p>
 */
@Service
@Transactional
public class ThesisService {

    private static final Logger log = LoggerFactory.getLogger(ThesisService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final ThesisDocumentRepository repository;
    private final ThesisOutlineGenerator generator;
    private final ThesisModelOutlineGenerator modelGenerator;
    private final ThesisOutlineNormalizer normalizer;
    private final ThesisOutlineCodec codec;
    private final ThesisCatalogService catalogService;
    private final ThesisFullTextGenerator fullTextGenerator;
    private final ThesisReferenceService referenceService;
    private final ObjectMapper objectMapper;

    public ThesisService(ThesisDocumentRepository repository,
                         ThesisOutlineGenerator generator,
                         ThesisModelOutlineGenerator modelGenerator,
                         ThesisOutlineNormalizer normalizer,
                         ThesisOutlineCodec codec,
                         ThesisCatalogService catalogService,
                         ThesisFullTextGenerator fullTextGenerator,
                         ThesisReferenceService referenceService,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.generator = generator;
        this.modelGenerator = modelGenerator;
        this.normalizer = normalizer;
        this.codec = codec;
        this.catalogService = catalogService;
        this.fullTextGenerator = fullTextGenerator;
        this.referenceService = referenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds a tree without touching the database.
     *
     * <p>Two engines, in this order: the model (only when the caller asks for it <em>and</em> an
     * upstream key is configured) then the deterministic template. The template is not a degraded
     * mode — it is what makes the free step free, and it is always the fallback so an upstream
     * outage never breaks a working feature.</p>
     *
     * @param currentUser caller for relay-billed model calls, {@code null} for a guest
     */
    public ThesisOutlineResult preview(ThesisOutlineRequest request, String modelKey,
                                       Long siteId, CurrentUser currentUser) {
        int depth = depth(request);
        String tier = tier(request);

        if (Boolean.TRUE.equals(request.useModel()) && modelGenerator.available()) {
            List<ThesisOutlineNode> generated = modelGenerator.generate(
                    modelKey,
                    request.educationLevel(),
                    request.paperType(),
                    request.discipline(),
                    request.major(),
                    request.topic(),
                    depth,
                    Boolean.TRUE.equals(request.dataModelEnabled()),
                    siteId,
                    currentUser);
            if (!generated.isEmpty()) {
                // 回填实际使用的模型 key：调用方可能没指定（用服务端默认），
                // 但响应要能说清「到底是哪个模型生成的」。
                String used = modelKey == null || modelKey.isBlank()
                        ? modelGenerator.resolvedModelKey()
                        : modelKey;
                return new ThesisOutlineResult(
                        ensureDataAppendix(generated, Boolean.TRUE.equals(request.dataModelEnabled())),
                        ThesisOutlineResult.MODE_MODEL, used);
            }
        }

        List<ThesisOutlineNode> templated = generator.build(tier, request.paperType(),
                request.discipline(), request.major(), request.topic(),
                Boolean.TRUE.equals(request.dataModelEnabled()), depth);
        return new ThesisOutlineResult(
                ensureDataAppendix(templated, Boolean.TRUE.equals(request.dataModelEnabled())),
                ThesisOutlineResult.MODE_TEMPLATE, null);
    }

    /**
     * 保证「数据模型与代码」开关在结果里真的生效。
     *
     * <p>模板引擎自己会加附录；模型引擎只是被「请求」加附录，不保证照办 —— 实测里模型经常只写
     * 五章正文就收尾。开关是用户勾的、也是收费承诺，所以缺了就补：插在「参考文献」之前，
     * 与该章同级，编号用 A.x 不会和正文撞号。</p>
     */
    private List<ThesisOutlineNode> ensureDataAppendix(List<ThesisOutlineNode> nodes, boolean enabled) {
        if (!enabled || nodes == null || nodes.isEmpty()) {
            return nodes;
        }
        boolean present = nodes.stream()
                .anyMatch(node -> ThesisFullTextGenerator.isDataAppendix(node.title()));
        if (present) {
            return nodes;
        }
        List<ThesisOutlineNode> merged = new ArrayList<>(nodes);
        int insertAt = merged.size();
        for (int i = 0; i < merged.size(); i++) {
            String title = merged.get(i).title();
            if (title != null && (title.contains("参考文献") || title.contains("致谢"))) {
                insertAt = i;
                break;
            }
        }
        merged.addAll(insertAt, generator.dataAppendix());
        return List.copyOf(merged);
    }

    /**
     * Which engine will serve the next request, and which model key it would use.
     *
     * <p>Surfaced on {@code GET /catalog} so the client can label the button honestly instead of
     * promising a model call the server cannot make.</p>
     */
    public boolean modelAvailable() {
        return modelGenerator.available();
    }

    /**
     * @param outline the tree the client renders
     * @param mode    {@code MODEL} when the headings came from a language model, {@code TEMPLATE}
     *                when they came from the deterministic engine
     * @param model   model key that produced them, {@code null} for the template
     */
    public record ThesisOutlineResult(List<ThesisOutlineNode> outline, String mode, String model) {
        public static final String MODE_MODEL = "MODEL";
        public static final String MODE_TEMPLATE = "TEMPLATE";
    }

    /**
     * Template selector.
     *
     * <p>The education level is the primary input; the legacy {@code tier} field is only read when
     * no education level came in, so an old client that still sends {@code tier} keeps working while
     * the new form never has to know the internal names.</p>
     */
    /**
     * Which chapter skeleton to use.
     *
     * <p>The paper type wins over the education level, because the type is the more specific
     * statement about what the document is: 「开题报告」is a proposal no matter whether the author is
     * an undergraduate or a PhD candidate, and 「期刊文章」does not become a 系统实现 chapter just
     * because the education level says 本科. Education still decides depth and default word count.
     * </p>
     *
     * <p>When the type has its own skeleton the tier is irrelevant, but it is still reported
     * consistently so the stored value matches what was generated.</p>
     */
    private String tier(ThesisOutlineRequest request) {
        PaperTypeProfile profile = PaperTypeProfile.byLabel(request.paperType());
        if (profile.skeleton() != null) {
            return profile.tier();
        }
        if (request.educationLevel() != null && !request.educationLevel().isBlank()) {
            String fromEducation = catalogService.tierForEducation(request.educationLevel());
            // Degree types (论文助手 / 期刊文章 / 开题报告 / 研究生小文章) have no dedicated skeleton,
            // so their own tier is what makes them differ: 期刊文章 must not fall back to the
            // undergraduate 系统实现 chapter, and 开题报告 must reach the proposal branch.
            return profile == PaperTypeProfile.THESIS_ASSISTANT ? fromEducation : profile.tier();
        }
        return request.tier() == null || request.tier().isBlank()
                ? ThesisCatalogService.TIER_UNDERGRAD
                : request.tier();
    }

    /** Depth: explicit value wins, then the education level default, then three. */
    private int depth(ThesisOutlineRequest request) {
        if (request.outlineDepth() != null) {
            return request.outlineDepth();
        }
        if (request.educationLevel() != null && !request.educationLevel().isBlank()) {
            return catalogService.defaultDepth(request.educationLevel());
        }
        return 3;
    }

    /** Generates an outline and stores it as a new draft. */
    public ThesisDocumentResponse create(Long userId, Long siteId, ThesisOutlineRequest request) {
        String tier = tier(request);
        int depth = depth(request);
        List<ThesisOutlineNode> outline = ensureDataAppendix(
                generator.build(tier, request.paperType(),
                        request.discipline(), request.major(), request.topic(),
                        Boolean.TRUE.equals(request.dataModelEnabled()), depth),
                Boolean.TRUE.equals(request.dataModelEnabled()));
        int targetWords = request.targetWords() != null
                ? request.targetWords()
                : request.educationLevel() != null
                        ? catalogService.defaultWords(request.educationLevel())
                        : catalogService.defaultWordsForTier(tier);
        ThesisDocumentEntity entity = new ThesisDocumentEntity(
                userId,
                siteId,
                request.topic().strip(),
                tier,
                request.educationLevel(),
                request.paperType(),
                depth,
                request.discipline(),
                request.major(),
                targetWords,
                !Boolean.FALSE.equals(request.referenceEnabled()),
                !Boolean.FALSE.equals(request.attachmentsEnabled()),
                Boolean.TRUE.equals(request.dataModelEnabled()),
                codec.write(outline),
                ThesisDocumentEntity.STATUS_OUTLINE);
        ThesisDocumentEntity saved = repository.save(entity);
        return ThesisDocumentResponse.of(saved, outline);
    }

    @Transactional(readOnly = true)
    public ThesisDocumentResponse get(Long userId, Long id) {
        ThesisDocumentEntity entity = require(userId, id);
        return ThesisDocumentResponse.of(entity, codec.read(entity.getOutlineJson()));
    }

    @Transactional(readOnly = true)
    public PageResponse<ThesisDocumentSummaryResponse> list(Long userId, String keyword, Integer page, Integer size) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(pageIndex, pageSize);
        Page<ThesisDocumentEntity> result = keyword == null || keyword.isBlank()
                ? repository.findByUserIdAndStatusNotOrderByUpdatedAtDescIdDesc(
                        userId, ThesisDocumentEntity.STATUS_DELETED, pageable)
                : repository.findByUserIdAndStatusNotAndTopicContainingIgnoreCaseOrderByUpdatedAtDescIdDesc(
                        userId, ThesisDocumentEntity.STATUS_DELETED, keyword.strip(), pageable);
        List<ThesisDocumentSummaryResponse> items = result.getContent().stream()
                .map(e -> new ThesisDocumentSummaryResponse(
                        e.getId(),
                        e.getTopic(),
                        e.getEducationLevel() == null ? e.getTier() : e.getEducationLevel(),
                        e.getPaperType(),
                        e.getOutlineDepth(),
                        e.getDiscipline() + " / " + e.getMajor(),
                        e.getTargetWords(),
                        e.getStatus(),
                        e.getOutlineVersion(),
                        e.hasFullText(),
                        e.getFullTextWordCount(),
                        e.getFullTextGeneratedAt(),
                        e.getUpdatedAt()))
                .toList();
        return PageResponse.of(items, result.getTotalElements(), pageIndex, pageSize);
    }

    /** Replaces the whole tree; the editor sends what it wants, not a diff. */
    public ThesisDocumentResponse saveOutline(Long userId, Long id, ThesisOutlineUpdateRequest request) {
        ThesisDocumentEntity entity = require(userId, id);
        List<ThesisOutlineNode> normalized = normalizer.normalize(request.outline());
        entity.updateOutline(codec.write(normalized), maxDepth(normalized));
        boolean topicChanged = request.topic() != null && !request.topic().isBlank();
        if (topicChanged || request.targetWords() != null) {
            entity.updateForm(
                    topicChanged ? request.topic().strip() : entity.getTopic(),
                    entity.getDiscipline(),
                    entity.getMajor(),
                    request.targetWords() == null ? entity.getTargetWords() : request.targetWords(),
                    entity.isReferenceEnabled(),
                    entity.isAttachmentsEnabled(),
                    entity.isDataModelEnabled());
        }
        // 必须 flush 再读回：updatedAt 是 @UpdateTimestamp，只在 flush 时赋值。
        // 直接返回托管实体拿到的还是上一次的时间，客户端「已自动保存 · 时间」就永远停在第一版。
        return ThesisDocumentResponse.of(repository.saveAndFlush(entity), normalized);
    }

    /** Soft delete: the row stays for the 24h retention audit trail, lists skip it. */
    public void delete(Long userId, Long id) {
        ThesisDocumentEntity entity = require(userId, id);
        entity.markDeleted();
    }

    /** Marks a draft as being written; called by the full text flow once it is unlocked. */
    public ThesisDocumentResponse changeStatus(Long userId, Long id, String status) {
        ThesisDocumentEntity entity = require(userId, id);
        entity.changeStatus(status);
        return ThesisDocumentResponse.of(entity, codec.read(entity.getOutlineJson()));
    }

    @Transactional(readOnly = true)
    public long count(Long userId) {
        return repository.countByUserIdAndStatusNot(userId, ThesisDocumentEntity.STATUS_DELETED);
    }

    /** The depth actually present in the saved tree, which is what the client should be told. */
    private static int maxDepth(List<ThesisOutlineNode> nodes) {
        int max = 0;
        for (ThesisOutlineNode node : nodes) {
            max = Math.max(max, Math.max(node.level(), maxDepth(node.children())));
        }
        return max;
    }


    /* ----------------------------- 全文生成 ----------------------------- */

    /**
     * Billing gate for a full-text run, checked before the SSE stream is opened.
     *
     * <p>正文与大纲、对话走同一套计费：New API 是唯一权威。这里为调用者取到自己的中继凭据
     * （必要时现场开通），读一次账户余额，余额为零按 {@code 402} 拒绝，凭据缺失/网关不可用按
     * {@code 503}。放在开流之前而不是第一章里，是为了让客户端拿到正常的 HTTP 状态码与中文原因，
     * 而不是一个已经转起来的进度弹窗在第一章报错。</p>
     *
     * <p>未开启中继（本地开发，共用静态 key）时不要求登录；此时没有可扣费账户，
     * 只校验 key 是否配置。</p>
     */
    public void checkFullTextBilling(CurrentUser currentUser) {
        try {
            fullTextGenerator.checkBillable(currentUser);
        } catch (com.hub.backend.common.exception.PaymentRequiredException exception) {
            // 共享的余额判断用的是英文，留给其它模块的调用方与测试；论文页面向最终用户，
            // 换一句能直接照做的话（去哪、做什么），不把 New API 的内部措辞丢给用户。
            throw new com.hub.backend.common.exception.PaymentRequiredException(
                    "账户余额不足：全文按 token 计费，请先充值后重试");
        }
    }

    /**
     * Writes the whole thesis body, one chapter per model call.
     *
     * <p>Preconditions are checked here rather than in the client:</p>
     * <ul>
     *   <li>an outline is required — writing prose against no structure produces a shapeless
     *       essay, and the outline is the thing the user already reviewed;</li>
     *   <li>a model must be reachable — the template engine can plan headings but cannot write
     *       text, so there is no fallback for this step and the client is told so instead of
     *       receiving a stub;</li>
     *   <li>a signed-in caller is required when relay billing is on, because that is who holds the
     *       credential being spent.</li>
     * </ul>
     *
     * @param onProgress called before each chapter; may be {@code null}
     */
    /**
     * Writes the whole thesis body.
     *
     * <p>Deliberately runs without a transaction: each chapter is a synchronous model call, so the
     * complete run takes 1–3 minutes. The class-level {@code @Transactional} would hold a pooled
     * database connection for that entire window — a handful of concurrent users would then
     * exhaust the pool and stall unrelated requests. The method only reads (the outline lookup) and
     * writes nothing, so there is no atomicity to lose.</p>
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ThesisFullTextResponse generateFullText(Long userId,
                                                  Long siteId,
                                                  ThesisFullTextRequest request,
                                                  CurrentUser currentUser,
                                                  Consumer<ThesisFullTextGenerator.Progress> onProgress) {
        // 传了 documentId 就以库里那份稿件为准：一次取出，后面每个字段都「请求体优先、库里兜底」。
        // 以前只有大纲和题目回落数据库，targetWords / referenceEnabled 等直接吃默认值 ——
        // 客户端只发 {"documentId": X} 时，3000 字的稿子会按 8000 字写、开了文献引用也会丢掉。
        ThesisDocumentEntity saved = request.documentId() == null
                ? null
                : require(userId, request.documentId());

        List<ThesisOutlineNode> outline = resolveOutline(saved, request);
        if (outline.isEmpty()) {
            throw new com.hub.backend.common.exception.InvalidRequestException(
                    "生成全文前需要先有大纲：请先生成或保存大纲");
        }
        if (!modelGenerator.available()) {
            throw new com.hub.backend.common.exception.InvalidRequestException(
                    "服务端未配置可用的上游模型，全文生成不可用（大纲仍可用模板）");
        }

        String topic = resolveTopic(saved, request);
        if (topic == null || topic.isBlank()) {
            throw new com.hub.backend.common.exception.InvalidRequestException("缺少论文题目");
        }

        boolean referenceEnabled = pick(request.referenceEnabled(), saved, ThesisDocumentEntity::isReferenceEnabled, false);
        boolean attachmentsEnabled = pick(request.attachmentsEnabled(), saved, ThesisDocumentEntity::isAttachmentsEnabled, false);
        boolean dataModelEnabled = pick(request.dataModelEnabled(), saved, ThesisDocumentEntity::isDataModelEnabled, false);
        int targetWords = request.targetWords() != null && request.targetWords() > 0
                ? request.targetWords()
                : saved != null && saved.getTargetWords() > 0 ? saved.getTargetWords() : 8000;
        int depth = request.outlineDepth() != null
                ? request.outlineDepth()
                : saved != null && saved.getOutlineDepth() > 0 ? saved.getOutlineDepth() : 3;
        String major = firstNonBlank(request.major(), saved == null ? null : saved.getMajor());
        String discipline = firstNonBlank(request.discipline(), saved == null ? null : saved.getDiscipline());
        String educationLevel = firstNonBlank(request.educationLevel(),
                saved == null ? null : saved.getEducationLevel());
        String paperType = firstNonBlank(request.paperType(), saved == null ? null : saved.getPaperType());

        // References are fetched before writing so every chapter sees the same list; a chapter-by
        // chapter fetch would give each chapter a different bibliography.
        // Keywords, not the whole topic: OpenAlex treats a sentence as an OR match, which is how a
        // thesis on financial sentiment ended up citing a paper about interior design.
        List<ThesisReferenceService.Reference> references = referenceEnabled
                ? referenceService.search(referenceQuery(topic, major, discipline), 10)
                : List.of();

        ThesisFullTextGenerator.Brief brief = new ThesisFullTextGenerator.Brief(
                topic,
                educationLevel,
                paperType,
                discipline,
                major,
                depth,
                targetWords,
                referenceEnabled,
                attachmentsEnabled,
                dataModelEnabled,
                request.material());

        ThesisFullTextGenerator.Result produced = fullTextGenerator.generate(
                brief, request.model(), siteId, outline, references, currentUser, onProgress);
        List<ThesisFullTextGenerator.Chapter> written = produced.chapters();

        /*
         * 摘要放在正文之后、组装响应之前。
         *
         * 顺序上的两个理由：
         *  1. 摘要必须读正文才能写 —— 只给题目会写出一篇「理想中的论文」的摘要，
         *     与正文的方法和结论对不上，答辩时最容易被追问；
         *  2. 它要进响应、也要存库，晚于 sections 组装会导致第一次生成的稿件没有摘要，
         *     用户重新打开阅读器又得再点一次「生成摘要」。
         *
         * 进度上单独推一格：正文写完到摘要写完之间还有一次模型调用（十几秒），
         * 不推的话进度条满格不动，看起来像卡死。
         */
        if (onProgress != null) {
            onProgress.accept(new ThesisFullTextGenerator.Progress(
                    written.size(), written.size(), ThesisFullTextGenerator.ABSTRACT_TITLE));
        }
        ThesisFullTextGenerator.Abstract abstractResult = fullTextGenerator.writeAbstract(
                brief, request.model(), siteId, written, currentUser);
        int totalTokens = fullTextGenerator.drainTokens();

        List<ThesisFullTextResponse.Section> sections = new ArrayList<>();
        for (ThesisFullTextGenerator.Chapter chapter : written) {
            sections.add(new ThesisFullTextResponse.Section(chapter.title(), chapter.level(), chapter.content()));
        }

        List<ThesisFullTextResponse.Reference> referenceList = new ArrayList<>();
        int index = 1;
        for (ThesisReferenceService.Reference reference : references) {
            referenceList.add(new ThesisFullTextResponse.Reference(
                    index++, reference.citation(), reference.title(), reference.doi()));
        }

        // 参考文献 and 致谢 come from the citation list, not from the model: that is the one way to
        // guarantee the bibliography contains only works that exist.
        if (outline.stream().anyMatch(node -> node.title() != null && node.title().contains("参考文献"))) {
            sections.add(new ThesisFullTextResponse.Section("参考文献", 1, renderBibliography(referenceList)));
        }
        if (outline.stream().anyMatch(node -> node.title() != null && node.title().contains("致谢"))) {
            sections.add(new ThesisFullTextResponse.Section("致谢", 1, renderAcknowledgement(brief)));
        }

        // 正文口径：不含附录（表结构与代码）、参考文献、致谢。
        // 这三块都不是「论文正文字数」，学生对照目标字数核对工作量时比的正是正文；
        // 把参考文献和致谢算进去，8000 字的目标会凭空多出五六百字。
        int wordCount = sections.stream()
                .filter(section -> isBodySection(section.title()))
                .mapToInt(section -> ThesisFullTextGenerator.countWords(section.content()))
                .sum();
        String model = produced.modelKey();
        // Stated plainly rather than implied: the reader deserves to know which index these came
        // from and that only DOI-bearing records were kept, because that is why a thesis on a niche
        // topic may get a short list.
        String referenceSource = referenceEnabled
                ? "OpenAlex 开放学术索引（仅保留带 DOI、可核验的中文文献）"
                : "本次未启用文献引用";
        // 压缩是「尽力而为」的最后一道保险：模型失败时宁可交付略长的正文，也不能把内容截断。
        // 因此这里只如实报告是否仍然超出，由客户端明确告知用户，而不是悄悄改小数字。
        int ceiling = (int) Math.floor(targetWords * (1 + ThesisFullTextGenerator.WordBudget.MAX_OVERSHOOT));
        if (wordCount > ceiling) {
            log.warn("Thesis {} finished at {} chars, above the {}% ceiling ({}); target {}",
                    topic, wordCount, (int) (ThesisFullTextGenerator.WordBudget.MAX_OVERSHOOT * 100),
                    ceiling, targetWords);
        }
        ThesisFullTextResponse response = new ThesisFullTextResponse(topic, model, List.copyOf(sections),
                List.copyOf(referenceList), referenceSource, wordCount, targetWords, totalTokens,
                abstractResult.chinese(), abstractResult.chineseKeywords(),
                abstractResult.english(), abstractResult.englishKeywords());
        // 存回稿件：「我的稿件」要能再打开它。之前全文只活在前端内存里，关掉阅读器就没了，
        // 列表里也就只剩一个订单号可看。
        persistFullText(userId, request.documentId(), response);
        return response;
    }

    /** Stores the generated body so the reader can be reopened later. Never fails the generation. */
    private void persistFullText(Long userId, Long documentId, ThesisFullTextResponse response) {
        if (userId == null || documentId == null) {
            return;
        }
        repository.findByIdAndUserIdAndStatusNot(documentId, userId, ThesisDocumentEntity.STATUS_DELETED)
                .ifPresentOrElse(entity -> {
                    try {
                        entity.saveFullText(objectMapper.writeValueAsString(response),
                                response.model(), response.wordCount());
                        repository.save(entity);
                    } catch (JsonProcessingException exception) {
                        // 正文已经生成并返回给用户了，存储失败不该让这次生成变成错误页。
                        log.warn("Failed to persist full text for document {}: {}",
                                documentId, exception.getMessage());
                    }
                }, () -> log.warn("Full text not persisted: document {} not found for user {}",
                        documentId, userId));
    }

    /** The stored full text of a draft, for 「我的稿件 → 查看全文」. */
    @Transactional(readOnly = true)
    public ThesisFullTextResponse fullText(Long userId, Long id) {
        ThesisDocumentEntity entity = require(userId, id);
        if (!entity.hasFullText()) {
            throw new NotFoundException("该稿件还没有生成全文，打开大纲后点「生成全文」即可");
        }
        try {
            return repairCitations(objectMapper.readValue(entity.getFullTextJson(), ThesisFullTextResponse.class));
        } catch (JsonProcessingException exception) {
            log.error("Stored full text of document {} cannot be read: {}", id, exception.getMessage());
            throw new IllegalStateException("已保存的全文无法读取，请重新生成");
        }
    }

    /**
     * 修掉库里老全文的参考文献作者顺序。
     *
     * <p>引用文本是生成时拼好存进去的字符串，改生成器只对下一篇生效。已经生成过的稿件若
     * 不在这里修，用户就得为了一个格式 bug 重新花钱生成整篇 —— 而导出 Word 是交付物，
     * 「钰琪 杨」这种名字会直接被导师退回来。修复是幂等的：已经正确的条目不发生变化，
     * 参考文献正文（sections 里那一节）同步重写，避免阅读器与 Word 两处不一致。</p>
     */
    private ThesisFullTextResponse repairCitations(ThesisFullTextResponse fullText) {
        if (fullText == null || fullText.references() == null || fullText.references().isEmpty()) {
            return fullText;
        }
        List<ThesisFullTextResponse.Reference> repaired = new ArrayList<>();
        boolean changed = false;
        for (ThesisFullTextResponse.Reference reference : fullText.references()) {
            String fixed = ThesisReferenceService.repairLegacyCitation(reference.citation());
            if (!fixed.equals(reference.citation())) {
                changed = true;
            }
            repaired.add(new ThesisFullTextResponse.Reference(
                    reference.index(), fixed, reference.title(), reference.doi()));
        }
        if (!changed) {
            return fullText;
        }

        // 参考文献那一节的正文是同一批 citation 拼出来的，必须一起换，否则阅读器里
        // 看到的还是旧名字，用户会以为没修。
        List<ThesisFullTextResponse.Section> sections = fullText.sections() == null
                ? List.of()
                : fullText.sections().stream()
                .map(section -> section.title() != null && section.title().contains("参考文献")
                        ? new ThesisFullTextResponse.Section(
                        section.title(), section.level(), renderBibliography(repaired))
                        : section)
                .toList();

        return new ThesisFullTextResponse(
                fullText.topic(), fullText.model(), sections, repaired, fullText.referenceSource(),
                fullText.wordCount(), fullText.targetWords(), fullText.totalTokens(),
                fullText.abstractZh(), fullText.keywordsZh(), fullText.abstractEn(), fullText.keywordsEn());
    }

    /* ----------------------------- 摘要补生成 ----------------------------- */

    /**
     * 为一份**已经生成过全文**的稿件单独补写摘要与关键词。
     *
     * <p><b>为什么需要这个接口。</b>摘要是随正文一起生成的，但两种情况下用户手里会有一份
     * 「没有摘要的全文」：一是这个功能上线前生成的旧稿件（存进库的 JSON 里没有摘要字段），
     * 二是摘要那一次调用失败（上游空响应），我们刻意不让它毁掉已经写好的正文。
     * 两种情况都不该让用户重新花钱生成整篇 —— 正文就在库里，只需要最后那一千多 token。</p>
     *
     * <p><b>为什么不重跑正文。</b>重新生成会得到一篇措辞不同的正文，
     * 用户此前的手工修改（如果有）会全部作废；而且成本是补摘要的十几倍。</p>
     *
     * @return 补好摘要的完整全文（已存回稿件）
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ThesisFullTextResponse regenerateAbstract(Long userId, Long documentId,
                                                     String modelKey, Long siteId,
                                                     CurrentUser currentUser) {
        ThesisDocumentEntity entity = require(userId, documentId);
        if (!entity.hasFullText() || entity.getFullTextJson() == null) {
            throw new NotFoundException("该稿件还没有生成全文，无法单独生成摘要");
        }
        ThesisFullTextResponse stored;
        try {
            stored = objectMapper.readValue(entity.getFullTextJson(), ThesisFullTextResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已保存的全文无法读取，请重新生成");
        }

        List<ThesisFullTextGenerator.Chapter> chapters = stored.sections().stream()
                .map(section -> new ThesisFullTextGenerator.Chapter(
                        section.title(), section.level(), section.content()))
                .toList();
        ThesisFullTextGenerator.Brief brief = new ThesisFullTextGenerator.Brief(
                stored.topic(),
                entity.getEducationLevel(),
                entity.getPaperType(),
                entity.getDiscipline(),
                entity.getMajor(),
                entity.getOutlineDepth(),
                stored.targetWords(),
                entity.isReferenceEnabled(),
                entity.isAttachmentsEnabled(),
                entity.isDataModelEnabled(),
                null);

        ThesisFullTextGenerator.Abstract generated = fullTextGenerator.writeAbstract(
                brief, modelKey, siteId, chapters, currentUser);
        if (generated.isEmpty()) {
            throw new com.hub.backend.common.exception.InvalidRequestException(
                    "摘要生成失败：模型没有按要求返回中英文摘要，请稍后重试");
        }

        // 其余字段原样保留：补摘要不该改动正文、文献列表或字数口径。
        // 但仍要过一遍引用修复：这份稿件可能是修复上线前生成的，用户点「生成摘要」
        // 顺带打开阅读器，看到的参考文献不该还是「钰琪 杨」。
        ThesisFullTextResponse updated = repairCitations(new ThesisFullTextResponse(
                stored.topic(), stored.model(), stored.sections(), stored.references(),
                stored.referenceSource(), stored.wordCount(), stored.targetWords(),
                stored.totalTokens() + fullTextGenerator.drainTokens(),
                generated.chinese(), generated.chineseKeywords(),
                generated.english(), generated.englishKeywords()));
        persistFullText(userId, documentId, updated);
        return updated;
    }

    /** 正文章节：不是附录、不是参考文献、不是致谢。 */
    private static boolean isBodySection(String title) {
        return !ThesisFullTextGenerator.isDataAppendix(title)
                && (title == null || (!title.contains("参考文献") && !title.contains("致谢")));
    }

    /**
     * Builds a keyword phrase from the topic.
     *
     * <p>Strips the academic boilerplate that carries no topic meaning — 「基于」「研究」「设计与实现」
     * appear in a third of all Chinese thesis titles, so keeping them makes the search return works
     * about writing methodology rather than about the subject.</p>
     */
    static String referenceQuery(String topic, String major, String discipline) {
        String cleaned = topic == null ? "" : topic;
        // Longest first: 「设计与实现」 must be removed before 「的」 can split it.
        for (String filler : new String[]{"设计与实现", "的分析系统", "分析系统", "系统设计与实现",
                "系统设计", "系统实现", "的构建", "基于", "面向", "研究", "初探", "探析", "——", "的"}) {
            cleaned = cleaned.replace(filler, " ");
        }

        StringBuilder query = new StringBuilder(cleaned.replaceAll("\\s+", " ").strip());
        // Academic synonyms: 舆情 is a media word, and indexing it returns newspaper commentary from
        // ten years ago. The papers that would actually be cited for this topic say 情感分析 / 文本挖掘.
        String academic = academicPhrases(cleaned);
        if (!academic.isBlank()) {
            // Dot separated: "" is not usable here, but '.' is safe because no term has one.
            query.append(' ').append(academic);
        }
        // The major is added as its distinctive nouns only. "计算机科学与技术" is a programme name,
        // not a topic: searched as one phrase it matches nothing, and it would double as a filter
        // requirement that no real paper title satisfies.
        String majorWords = distinctiveWords(major);
        if (!majorWords.isBlank()) {
            query.append(' ').append(majorWords);
        } else if (discipline != null && !discipline.isBlank()) {
            query.append(' ').append(discipline.strip());
        }
        return query.toString().strip();
    }

    /**
     * Maps the topic's everyday vocabulary onto the words papers actually use in their titles.
     *
     * <p>This exists because of a measured gap, not a hunch: searching 中文文献 for 金融舆情 returns
     * 415 results that are almost all newspaper commentary (金融危机影响中国的舆情分析, 农村金融机构如何应对
     * 负面舆情), while 情感分析 股票 returns recent BERT-based studies on exactly this subject. A thesis
     * bibliography needs the latter, so the query carries both: the user's words, and their
     * academic equivalents.</p>
     */
    private static String academicPhrases(String topic) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        map.put("舆情", "情感分析 文本挖掘 网络舆情");
        map.put("情感", "情感分析 文本分类");
        map.put("股票", "股票 股指 收益率");
        map.put("金融", "金融 金融文本 风险管理");
        map.put("推荐", "推荐系统 协同过滤");
        map.put("图像", "图像识别 卷积神经网络");
        map.put("医疗", "智慧医疗 健康信息");
        map.put("教育", "教育信息化 教学设计");
        map.put("乡村", "乡村振兴 农村电商");
        map.put("电商", "电子商务 直播电商");
        map.put("物流", "物流配送 供应链");
        map.put("客服", "智能客服 对话系统");
        map.put("识别", "命名实体识别 文本分类");
        map.put("预测", "预测模型 时间序列");

        java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        for (var entry : map.entrySet()) {
            if (topic.contains(entry.getKey())) {
                picked.add(entry.getValue().split(" ")[0]);
            }
        }
        // Paired with the topic's own words to form two-term searches: 「情感分析」 alone returns art
        // criticism about 宋词, but 「情感分析 股票」 returns BERT-based stock sentiment studies.
        String lead = picked.isEmpty() ? "" : picked.iterator().next();
        if (lead.isBlank()) {
            return "";
        }
        // Space, not '.': a word pair is what makes the index return two-term papers, and
        // tokens() already splits on whitespace.
        boolean finance = topic.contains("金融") || topic.contains("股") || topic.contains("舆情");
        return finance ? lead + " 金融" : lead;
    }

    /**
     * Drops the words that name a degree programme rather than a subject.
     *
     * <p>「计算机科学与技术」 → 「计算机」. Chinese programme names have no spaces, so the split is
     * driven by the suffixes that appear in the names themselves. Splitting on whitespace alone left
     * the whole string intact, which then entered the search as one unmatchable phrase and dragged
     * the bibliography down to three entries.</p>
     *
     * <p>Only technology-flavoured suffixes are stripped. 「管理」 is deliberately absent: for 工商管理
     * or 工程管理 it is the subject, not a filler, and removing it turned the search into 「工商」.</p>
     */
    private static String distinctiveWords(String major) {
        if (major == null || major.isBlank()) {
            return "";
        }
        String cleaned = major.strip();
        for (String suffix : new String[]{"科学与技术", "科学与工程", "与工程", "科学与", "科学", "技术", "工程"}) {
            // Never strip down to a one-character stem: 「工程」 alone would become empty.
            if (cleaned.endsWith(suffix) && cleaned.length() - suffix.length() >= 2) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
            }
        }
        // 与 / 和 / 及 join two subject names: 计算机与软件 → 计算机 软件
        List<String> kept = new ArrayList<>();
        for (String word : cleaned.split("[与和及、/\\s]+")) {
            String trimmed = word.strip();
            if (trimmed.length() >= 2) {
                kept.add(trimmed);
            }
        }
        return kept.isEmpty() ? major.strip() : String.join(" ", kept);
    }

    /** Uses the stored document when an id was given, otherwise the inline outline. */
    private List<ThesisOutlineNode> resolveOutline(ThesisDocumentEntity saved, ThesisFullTextRequest request) {
        if (saved != null) {
            return codec.read(saved.getOutlineJson());
        }
        if (request.outline() == null || request.outline().isEmpty()) {
            return List.of();
        }
        return normalizer.normalize(request.outline());
    }

    private String resolveTopic(ThesisDocumentEntity saved, ThesisFullTextRequest request) {
        if (request.topic() != null && !request.topic().isBlank()) {
            return request.topic().strip();
        }
        return saved == null ? null : saved.getTopic();
    }

    /**
     * 「请求体给了就用请求体的，没给才读库」。
     *
     * <p>开关类字段用包装类型区分「没传」和「传了 false」：`{"documentId":1}` 里没有这个键，
     * 应当继承稿件保存时的选择；显式 `false` 才算用户这次真的关掉了它。</p>
     */
    private static boolean pick(Boolean requested,
                                ThesisDocumentEntity saved,
                                java.util.function.Predicate<ThesisDocumentEntity> fromDb,
                                boolean fallback) {
        if (requested != null) {
            return requested;
        }
        return saved == null ? fallback : fromDb.test(saved);
    }

    private static String firstNonBlank(String requested, String fromDb) {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        return fromDb == null || fromDb.isBlank() ? null : fromDb.strip();
    }

    private static String renderBibliography(List<ThesisFullTextResponse.Reference> references) {
        if (references.isEmpty()) {
            return "（本次未能从公开学术索引取到带 DOI 的可用条目。请通过学校图书馆的知网 / 万方入口检索后补充，"
                    + "不要凭印象填写——编造的参考文献在答辩时会被直接指出。）";
        }
        StringBuilder out = new StringBuilder();
        for (ThesisFullTextResponse.Reference reference : references) {
            out.append('[').append(reference.index()).append("] ")
                    .append(reference.citation()).append("\n");
        }
        return out.toString().strip();
    }

    /**
     * The acknowledgement is the one section where a template is acceptable: it is personal by
     * nature, so the honest thing is a labelled skeleton the user fills in, not generated flattery.
     */
    private static String renderAcknowledgement(ThesisFullTextGenerator.Brief brief) {
        return """
                本论文的完成离不开许多人的帮助，在此谨致以诚挚的谢意。

                首先感谢我的指导教师。从选题的确定、研究框架的搭建到论文的反复修改，老师都给予了耐心细致的指导，使我得以逐步厘清研究思路、完善论文结构。老师严谨的治学态度与务实的工作作风，是我今后学习与工作中需要长期坚持的方向。

                感谢在论文写作过程中给予帮助的各位老师与同学，他们对本文提出的意见使论文得以改进。

                感谢家人一直以来的理解与支持，使我能够安心完成学业。

                最后，向在百忙之中评阅本文的各位专家表示衷心的感谢。

                （以上为可编辑模板：请补入导师姓名、致谢对象等具体信息。）""";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取一份属于当前账号的稿件。
     *
     * <p>文案是中文的：这条异常会经由流式接口的 {@code event:error} 直接显示在进度弹窗里，
     * 以前的英文原话（{@code thesis document 999999 was not found}）就这么落到了用户眼前。
     * 「不存在」与「不属于当前账号」刻意用同一句话，不泄漏别人的稿件是否存在。</p>
     */
    private ThesisDocumentEntity require(Long userId, Long id) {
        return repository.findByIdAndUserIdAndStatusNot(id, userId, ThesisDocumentEntity.STATUS_DELETED)
                .orElseThrow(() -> new NotFoundException("稿件不存在或已被删除，请刷新「我的稿件」后重试"));
    }
}
