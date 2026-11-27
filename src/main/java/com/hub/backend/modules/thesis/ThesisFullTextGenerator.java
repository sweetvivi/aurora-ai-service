package com.hub.backend.modules.thesis;

import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.LlmFailures;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

/**
 * Writes the body text, one chapter per model call.
 *
 * <p><b>Why per chapter.</b> A 15,000-character thesis in one completion either gets truncated
 * ({@code finish_reason=length}), loses the later chapters, or drifts off the outline. Splitting by
 * 章 keeps every call inside the context window, lets the client show progress chapter by chapter,
 * and — the practical reason — means one failed chapter costs one retry instead of the whole book.</p>
 *
 * <p><b>What the model is given.</b> Every call carries the <em>whole</em> left-hand form: topic,
 * education level, discipline/major, paper type, outline depth, target word count, the three
 * switches (references, attachments, data model) and the full outline. That is redundant across
 * calls, but it is what makes each chapter obey the user's stated requirements rather than drifting:
 * a chapter generated in isolation cannot infer that the thesis is an undergraduate systems paper
 * with a data model, so the form is restated every time.</p>
 *
 * <p><b>References are facts, not inventions.</b> When the reference switch is on, the verified
 * citation list is inserted into the prompt as the only citable material, with an explicit
 * instruction not to invent works. {@code [n]} markers map to that list one-to-one, so the client
 * can render superscripts that actually resolve.</p>
 */
@Component
public class ThesisFullTextGenerator {

    private static final Logger log = LoggerFactory.getLogger(ThesisFullTextGenerator.class);

    /**
     * 单次请求输出上限的硬顶。
     *
     * <p>这一数值同时决定上游要预冻多少额度（预冻按 {@code max_tokens} 算，请求结束才按实际用量退回），
     * 所以要的是「够本章写完」而不是「越大越保险」。但它也不能太小：本地四个 CHAT 模型全是
     * 推理模型（{@code is_thinking=1}），思考与正文共用这一份预算，4096 时实测仍有概率出现
     * 「思考把预算吃光、正文为空」。8192 是够写一章 2000 字正文 + 一轮完整推演的余量。</p>
     *
     * <p>预冻的代价是可控的：额度只在请求进行期间占用，结束即按实际用量退回，
     * 所以上限抬高影响的是「短时间内的可用额度」，不是「实际花掉的钱」。</p>
     */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    /**
     * 单章输出下限。
     *
     * <p>必须容得下 {@code WordBudget} 给出的字数区间：一章正文八百来字、三成余量，
     * 再加一点缓冲才不至于在半句话上被截断。低于这个值的章节（一篇文章被切成很多章时会出现）
     * 仍然给足，宁可多冻一点，也不能交付一篇被截肢的正文。</p>
     *
     * <p>从 1536 → 2048 → 3072：推理模型的思考开销实测可达 1500–2500 token，
     * 预算扣掉思考后剩不下多少写正文，这是此前「第一章就空响应」的直接原因。</p>
     */
    private static final int MIN_OUTPUT_TOKENS = 3072;

    /** 附录（DDL / 代码）没有字数预算，给一个够用又不至于冻太多额度的值。 */
    private static final int APPENDIX_OUTPUT_TOKENS = 3072;

    /**
     * 中英文摘要的输出预算。
     *
     * <p>摘要本身不长（中文 300–800 字 + 英文 200–400 词），但它要**读完全文**才能写，
     * 所以和压缩一样属于「输入长、输出短」，预算按输出算、再叠思考余量即可。
     * 给 4096 是因为推理模型常在读完八千字后先花掉千余 token 梳理全文结构。</p>
     *
     * <p>原先是 3072，实测出现过一次「英文摘要停在半句、英文关键词整段丢失」：中文 607 字
     * + 英文 2238 字符已经把预算吃到见底，模型还没走到【英文关键词】就断流了。
     * 单跑补摘要接口 3/3 正常，说明不是解析问题，就是预算不够 —— 所以这里抬一档，
     * 并在 {@link #writeAbstract} 里对「缺英文关键词 / 英文没写完」再兜一次重试。</p>
     */
    private static final int ABSTRACT_OUTPUT_TOKENS = 4096;

    /**
     * 推理模型（{@code is_thinking}）单次调用额外要留的思考预算。
     *
     * <p>这类模型把「想」和「写」算在同一个 {@code max_tokens} 里：预算不够时它会把整份
     * 预算花在推演上，正文一个字都没输出，网关侧 {@code completion_tokens} 正好打满、
     * 记账成功，而后端收到的是空 content —— 现象就是「第 N 章模型调用失败」。</p>
     *
     * <p>从 1024 提到 2048、再到 2560：实测这个上游的空响应是概率性的，2048 仍会撞到，
     * 一次撞上就要多等 15–60 秒（重试抬预算能救回来，但白白多扣一轮预冻额度）。
     * 2560 把首次命中率明显拉高，代价是每次请求多预冻一点 —— 请求结束即按实际用量退回。</p>
     */
    private static final int THINKING_HEADROOM_TOKENS = 2560;

    /**
     * 超出这个字数以内就不值得调模型改写，直接按句子边界截掉尾巴。
     *
     * <p>改写一次的输入是整章原文、输出是整章新文，千字级的往返，实测单章 14–25 秒。
     * 为了省一两百字付这个代价不划算：实测 4000 字 / 4 章、超出 128 字时，
     * 改写两整章花了 43 秒，占整次生成的一半。而截掉一两句读者基本察觉不到，
     * 也不会像改写那样有「压成摘要」的风险。</p>
     *
     * <p>取 400：大约是单章 {@code MIN_CHAPTER_WORDS} 的规模，也是「一句话都删不掉」的量级 ——
     * 再大就确实该让模型重写了，因为纯截断会从某一章尾部删掉太多内容。</p>
     */
    private static final int SMALL_OVERSHOOT_WORDS = 400;

    /**
     * 瞬时失败的重试次数（含首次）。
     *
     * <p>中继网关偶发 429 / 5xx / 连接重置是常态，之前一次失败就整篇作废，
     * 用户看到的就是「有概率调用失败」。四试三等到 4 次是为了配合「空响应抬预算」：
     * 每次重试会把预算往上抬一档（+2048），最多试到顶格，给足收敛空间。</p>
     */
    private static final int MAX_ATTEMPTS = 4;

    /** 退避基数：第 n 次重试等 n × 800ms。 */
    private static final long RETRY_BACKOFF_MS = 800;

    /** 单章下限：再短就不像一节正文，压缩与截断都不会突破这条线。 */
    private static final int MIN_CHAPTER_WORDS = 400;

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final NewApiProperties newApiProperties;
    private final UserRelayCredentialResolver relayCredentials;
    private final ModelUseResolver modelResolver;

    /**
     * Tokens spent by the last {@link #generate} call.
     *
     * <p>Held on the bean rather than returned, because a generation is already a single-flight
     * operation per request and threading it through the chapter record would only add noise. The
     * service drains it once, right after the call.</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger lastTokens = new java.util.concurrent.atomic.AtomicInteger();

    public ThesisFullTextGenerator(LlmClient llmClient,
                                   LlmProperties llmProperties,
                                   NewApiProperties newApiProperties,
                                   UserRelayCredentialResolver relayCredentials,
                                   ModelUseResolver modelResolver) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.newApiProperties = newApiProperties;
        this.relayCredentials = relayCredentials;
        this.modelResolver = modelResolver;
    }

    /**
     * Everything the user typed into the left-hand form.
     *
     * <p>Carried as one record so the prompt builder cannot quietly forget a field — each one is
     * either rendered into the prompt or deliberately excluded with a comment.</p>
     */
    public record Brief(String topic,
                        String educationLevel,
                        String paperType,
                        String discipline,
                        String major,
                        int outlineDepth,
                        int targetWords,
                        boolean referenceEnabled,
                        boolean attachmentsEnabled,
                        boolean dataModelEnabled,
                        String material) {
    }

    /** One finished chapter, already carrying its heading. */
    public record Chapter(String title, int level, String content) {
    }

    /**
     * @param chapters the written body in outline order
     * @param modelKey the <em>site</em> model key that produced it, echoed back to the client;
     *                 never the upstream name, which is an implementation detail of the gateway
     */
    public record Result(List<Chapter> chapters, String modelKey) {
    }

    /** Heading of the appendix the 「数据模型与代码」 switch promises. */
    public static final String APPENDIX_TITLE = "附录 A 数据模型与关键代码";

    /** 摘要阶段的进度文案：正文写完后、总结之前的那一格。 */
    public static final String ABSTRACT_TITLE = "撰写中英文摘要";

    /**
     * 中英文摘要与关键词。
     *
     * <p>这是论文的必备结构件，不是可选装饰：国内高校的送审格式里，中文摘要、中文关键词、
     * Abstract、Key words 四项缺一项就会被退回。此前它们由模型在第一章里「顺便」写，
     * 结果时有时无、章节里还夹着一段 Abstact —— 所以现在单独成一个阶段，
     * 由已经写好的正文倒推，格式由服务端解析后固定下来。</p>
     *
     * @param chinese         中文摘要正文（不含「摘要」二字，一段连续文字）
     * @param chineseKeywords 中文关键词，已按 3–5 个整理、以「；」分隔
     * @param english         英文摘要正文（不含「Abstract」）
     * @param englishKeywords 英文关键词，以「; 」分隔
     */
    public record Abstract(String chinese,
                           String chineseKeywords,
                           String english,
                           String englishKeywords) {

        /** 四项都为空时的空壳，用于「生成失败但正文仍要交付」的情形。 */
        public static Abstract empty() {
            return new Abstract("", "", "", "");
        }

        public boolean isEmpty() {
            return isBlank(chinese) && isBlank(english)
                    && isBlank(chineseKeywords) && isBlank(englishKeywords);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    /**
     * Shown on the progress line while the over-long chapters are being rewritten.
     *
     * <p>Deliberately vague. The rewrite is internal bookkeeping — the reader gets no useful
     * information from "压缩至 3200 字", and phrasing it as a compression invites them to think
     * content is being thrown away. What they need to know is only that the run is still alive.</p>
     */
    public static final String FINAL_POLISHING_TITLE = "最终整合中";

    /**
     * Generates chapter bodies in outline order.
     *
     * <p>Sequential on purpose: the chapters share one narrative (chapter 4 refers back to the
     * method in chapter 3), and the earlier ones are passed forward as a short digest so the model
     * does not repeat itself. Parallelising would be faster and read worse.</p>
     *
     * <p>When the data-model switch is on, one extra call writes the appendix. If the outline
     * already contains that appendix (both the template engine and the model outline put it there),
     * the chapter loop writes it like any other chapter and no second call is made — otherwise the
     * same table definitions would appear twice in the finished document.</p>
     *
     * @param chapters     top-level chapters to write; {@code 参考文献}/{@code 致谢} are skipped because
     *                     they are generated from the citation list rather than written by the model
     * @param references   verified citations, empty when the switch is off or the index failed
     * @param onProgress   called after each chapter so the client can show progress
     * @return bodies in the same order as the input chapters
     */
    public Result generate(Brief brief,
                           String modelKey,
                           Long siteId,
                           List<ThesisOutlineNode> chapters,
                           List<ThesisReferenceService.Reference> references,
                           CurrentUser currentUser,
                           Consumer<Progress> onProgress) {
        // 站点 key 与上游模型名是两回事：页面选的是 deepseek-v4.1-flash，网关要的可能是
        // deepseek-v4-1-flash-260910。以前这里把站点 key 原样透传，对话 / 智能体 / 画布都走了
        // 路由映射，只有论文全文这条 503 model_not_found。
        ModelUseResolver.Resolved model = modelResolver.resolveOrDefault(
                siteId, modelKey, com.hub.backend.modules.model.entity.LlmModelEntity.TYPE_CHAT);
        String key = model.upstreamKey();
        // 推理模型把思考算进同一个输出预算，写正文时要多留一份，否则正文可能一个字都写不出来
        boolean thinking = model.thinking();
        ProviderCredentials credentials = credentials(currentUser);

        List<ThesisOutlineNode> writable = chapters.stream()
                .filter(chapter -> !isBackMatter(chapter.title()))
                .toList();
        boolean appendixInOutline = writable.stream().anyMatch(c -> isDataAppendix(c.title()));
        boolean extraAppendix = brief.dataModelEnabled() && !appendixInOutline;
        int total = writable.size() + (extraAppendix ? 1 : 0);

        // 字数预算是整篇共用的，不是每章一份。之前每章各拿一个 800 字下限的独立预算，
        // 章数一多实际字数就冲破上限：8000 字 / 6 章 → 每章至少 800，模型给 1150+，
        // 加总 7000+ 之外还会因为章数被低估（参考文献与致谢占了一章名额）继续膨胀。
        WordBudget budget = new WordBudget(brief.targetWords(), Math.max(1, writable.size()));

        lastTokens.set(0);
        List<Chapter> written = new ArrayList<>();
        StringJoiner digest = new StringJoiner("\n");
        int index = 0;

        for (ThesisOutlineNode chapter : writable) {
            index++;
            String title = chapter.title();

            if (onProgress != null) {
                onProgress.accept(new Progress(index, total, title));
            }

            // 附录与正文是两种文本，走两条提示词。附录也不占正文字数预算：
            // DDL 与代码不是散文，用户填的字数是给正文的。
            if (isDataAppendix(title)) {
                String content = writeAppendix(key, credentials, brief, digest.toString().strip(), thinking).content();
                written.add(new Chapter(title, 1, content));
            } else {
                WordBudget.Range range = budget.next();
                String content = writeOne(key, credentials, brief, chapter, range,
                        references, digest.toString().strip(), thinking);
                written.add(new Chapter(title, 1, content));
                budget.spend(countWords(content));
            }
            digest.add(shortDigest(written.get(written.size() - 1)));
        }

        if (extraAppendix) {
            if (onProgress != null) {
                onProgress.accept(new Progress(total, total, APPENDIX_TITLE));
            }
            Chapter appendix = writeAppendix(key, credentials, brief, digest.toString().strip(), thinking);
            written.add(appendix);
        }

        // 提示词能把偏差压到个位数百分比，但压不到零：单章 token 上限、模型自身的展开欲、
        // 大纲小节数都会让某章明显超配。这里是最后一道保险 —— 超过目标 4% 才动手，
        // 收到目标值而不是 110% 的硬上限，这样交付落在用户填的字数以内。
        //
        // 这一步推一条进度：章节都写完了，进度条会停在最后一格等下去。压缩本身要再调几次
        // 模型（每章一次，一章可能要十几秒），不留消息的话看起来就是卡死了。
        // 文案只说「最终整合中」，不说「压缩至 3200 字」—— 那是内部返工，用户既不需要知道、
        // 也无从判断好坏；index/total 保持 total/total，进度条不会因此回退或超格。
        if (budget.shouldTrim()) {
            if (onProgress != null) {
                onProgress.accept(new Progress(total, total, FINAL_POLISHING_TITLE));
            }
            written = tightenToLimit(key, credentials, written, budget);
        }

        // 回显用站点 key（页面上的那个），上游模型名只用于发请求。
        return new Result(List.copyOf(written), model.requestKey());
    }

    /**
     * Shrinks the chapters that overspend their allowance until the document is back at target.
     *
     * <p>Rewrites the longest chapter first — the biggest saving per call — and stops as soon as the
     * total is inside the target. Only the overflowing part is asked to go: the model is given the
     * chapter's own text and an exact target length, so the result stays the same chapter rather
     * than a summary of it.</p>
     *
     * <p>Silent per chapter: the caller announces the stage once ({@link #FINAL_POLISHING_TITLE})
     * and nothing more. Which chapter is being rewritten, and to what length, is internal clean-up
     * rather than a step the reader can act on — a per-chapter tick here would push the bar past
     * 100% and invite the reader to audit numbers that are about to be replaced.</p>
     */
    private List<Chapter> tightenToLimit(String key,
                                         ProviderCredentials credentials,
                                         List<Chapter> written,
                                         WordBudget budget) {
        List<Chapter> result = new ArrayList<>(written);
        // 只收到「契约允许的范围内」，不再一路压回目标值。
        //
        // 以前是收到 target：既然花了一次改写，就交足用户填的字数。但那样每章的改写量都在
        // 一两千字，模型要重写整章、耗时十几秒起步，还容易压过头。现在触发点已经退到 110%
        // 附近，超出的多半只有一两百字，收到催交线以内的做法能用**小得多的改写量**达标，
        // 模型也更容易一次做完 —— 用户拿到的仍是「不超过目标 10%」的合规稿。
        int aimAt = budget.ceiling() - (int) Math.floor(budget.target() * 0.01);
        int over = budget.spent() - aimAt;
        log.info("Full text over: {} chars written, target {} (ceiling {}), trimming {} down to {}",
                budget.spent(), budget.target(), budget.ceiling(), budget.spent() - aimAt, aimAt);

        // 轻微超出不调模型：直接按句子边界截掉尾巴。
        //
        // 实测代价对比（4000 字 / 4 章）：超出 128 字时，改写两整章花了 43 秒
        // （1588→1462、1514→1467），占整次生成 84 秒的一半，而目的只是省 168 字。
        // 一次改写要读完整章再重写整章，输入输出都是千字级；为了百来字的差额付这个代价不划算，
        // 而且改写有把章节压成摘要的风险（见下面 overshoot 判断）。
        //
        // 截断只从最长那章的**句尾**删整句，删掉 1–2 句读者基本察觉不到；
        // 而改写会把整章重新生成一遍，反而更容易出错。
        if (over <= SMALL_OVERSHOOT_WORDS) {
            log.info("Overshoot of {} chars is small enough to cut at a sentence boundary", over);
            return hardTrim(result, aimAt);
        }

        // 每章最多压一次：反复改写既费 token 又会让文风漂移，一次压不到位就接受略超。
        boolean[] tried = new boolean[result.size()];
        while (over > 0) {
            int longest = -1;
            int longestWords = 0;
            for (int i = 0; i < result.size(); i++) {
                Chapter chapter = result.get(i);
                if (tried[i] || isDataAppendix(chapter.title())) {
                    continue;
                }
                int words = countWords(chapter.content());
                if (words > longestWords) {
                    longestWords = words;
                    longest = i;
                }
            }
            if (longest < 0 || longestWords <= 0) {
                break;
            }
            tried[longest] = true;

            Chapter chapter = result.get(longest);
            int target = Math.max(600, longestWords - over);
            if (target >= longestWords) {
                // 已经比目标还短，压缩没有意义
                continue;
            }
            String trimmed = trimChapter(key, credentials, chapter, target);
            int after = countWords(trimmed);
            if (after <= 0 || after >= longestWords) {
                // 压缩没生效（模型原样返回或返回了更长的版本）：记下这一章，换下一章，不死循环。
                log.warn("Trim of '{}' had no effect ({} -> {}), skipping", chapter.title(),
                        longestWords, after);
                continue;
            }
            // 压缩过头同样是失败：要求是「压到 target 字左右」，模型却常直接甩回一段几十字的
            // 摘要 —— 实测把 1602 字压成 84 字，正文被整段丢掉。这种结果不能接受，宁可保留原章
            // 让后面走确定性截断（hardTrim 有 MIN_CHAPTER_WORDS 兜底），也不能交付一篇残缺正文。
            int floor = Math.min(MIN_CHAPTER_WORDS, target);
            if (after < floor) {
                log.warn("Trim of '{}' overshot ({} -> {}, target {}, floor {}), keeping the original",
                        chapter.title(), longestWords, after, target, floor);
                continue;
            }

            result.set(longest, new Chapter(chapter.title(), chapter.level(), trimmed));
            over = Math.max(0, over - (longestWords - after));
            log.info("Trimmed '{}': {} -> {} chars", chapter.title(), longestWords, after);
        }

        // 改写没有收敛（模型拒绝压缩 / 调用失败 / 越改越长）：退到确定性截断。
        // 「不超过 110%」是写进契约的硬约束，不能因为上游不配合就违约；
        // 截断只发生在句子边界，宁可让最后一章少一段，也不交付一个超限的稿子。
        int total = result.stream()
                .filter(chapter -> !isDataAppendix(chapter.title()))
                .mapToInt(chapter -> countWords(chapter.content()))
                .sum();
        if (total > budget.ceiling()) {
            log.warn("Model trimming did not converge ({} chars), falling back to sentence cut at {}",
                    total, budget.ceiling());
            result = hardTrim(result, budget.ceiling());
        }
        return result;
    }

    /**
     * Last-resort cut at sentence boundaries.
     *
     * <p>Only used when rewriting failed: it shortens the longest chapter until the whole document
     * fits the ceiling. Sentences are dropped from the end, so the remaining text is still a valid
     * chapter — never a half sentence. A chapter is not cut below {@link #MIN_CHAPTER_WORDS}; if the
     * limit cannot be reached without gutting every chapter, the overrun is reported instead of
     * silently destroying the body.</p>
     */
    private List<Chapter> hardTrim(List<Chapter> chapters, int ceiling) {
        List<Chapter> result = new ArrayList<>(chapters);
        int total = result.stream()
                .filter(chapter -> !isDataAppendix(chapter.title()))
                .mapToInt(chapter -> countWords(chapter.content()))
                .sum();

        while (total > ceiling) {
            int longest = -1;
            int longestWords = 0;
            for (int i = 0; i < result.size(); i++) {
                Chapter chapter = result.get(i);
                if (isDataAppendix(chapter.title())) {
                    continue;
                }
                int words = countWords(chapter.content());
                if (words > longestWords) {
                    longestWords = words;
                    longest = i;
                }
            }
            if (longest < 0 || longestWords <= MIN_CHAPTER_WORDS) {
                log.error("Cannot reach the {} char ceiling without emptying a chapter ({} chars)",
                        ceiling, total);
                break;
            }
            Chapter chapter = result.get(longest);
            int keep = Math.max(MIN_CHAPTER_WORDS, longestWords - (total - ceiling));
            String cut = cutToSentenceBoundary(chapter.content(), keep);
            int after = countWords(cut);
            if (after >= longestWords) {
                break;
            }
            result.set(longest, new Chapter(chapter.title(), chapter.level(), cut));
            total = total - longestWords + after;
            log.info("Cut '{}' at a sentence boundary: {} -> {} chars", chapter.title(),
                    longestWords, after);
        }
        return result;
    }

    /**
     * Drops whole sentences from the end until the text is at most {@code keep} characters.
     *
     * <p>Chinese sentences end with 。！？; a paragraph that ends without punctuation still survives
     * because the split keeps at least one segment.</p>
     */
    static String cutToSentenceBoundary(String content, int keep) {
        if (content == null || countWords(content) <= keep) {
            return content;
        }
        String[] sentences = content.split("(?<=[。！？!?])");
        int take = sentences.length;
        while (take > 1 && countWords(String.join("", java.util.Arrays.copyOf(sentences, take))) > keep) {
            take--;
        }
        return String.join("", java.util.Arrays.copyOf(sentences, take)).strip();
    }

    /** One rewriting call that keeps the chapter's structure but cuts it to {@code target} words. */
    private String trimChapter(String key, ProviderCredentials credentials, Chapter chapter,
                               int target) {
        try {
            String content = complete(key, credentials, List.of(
                    LlmChatMessage.system("""
                            你是中文论文的编辑，只做一件事：在不改变观点、不删章节结构的前提下，
                            把稿子压到指定字数。你输出的是修改后的全文，不是修改说明。
                            """.strip()),
                    LlmChatMessage.user("""
                            下面是一章论文正文，当前约 %d 字，需要压缩到 %d 字左右（不少于 %d 字）。

                            要求：
                            1. 保留原有的段落顺序与每一个小节，不得合并或删除小节。
                            2. 删掉重复论证、空泛铺陈、同义反复的句子；保留数据、方法与结论的具体表述。
                            3. 不要新增内容，不要写「本章将」，不要输出任何说明文字。
                            4. 直接输出压缩后的正文，不要标题。

                            正文：
                            %s
                            """.formatted(countWords(chapter.content()), target,
                            (int) Math.floor(target * 0.95), chapter.content()))),
                    /*
                     * 压缩的预算按「目标字数」算，但**要叠加思考余量**。
                     *
                     * 两个方向都试过，各有各的坑：
                     *  - 按原文字数算（3000 字 → 4156）：上游按大额度预冻、按大额度生成，
                     *    实测单章压缩从十几秒拖到两分多钟，三轮叠加整篇超过 4 分钟，前端断连；
                     *  - 按 target 且不给思考余量（1000 字 → 2048）：压缩也要先读懂原文再删减，
                     *    2048 里扣掉思考就不够输出正文，实测频繁空响应，反而靠重试救场。
                     *
                     * 折中：target 决定正文长度，再加上与写正文同等的思考余量。
                     * 压缩输出必然短于原文，所以总额度仍明显低于「按原文算」，不会拖慢。
                     */
                    0.2, trimTokensFor(target));
            return normaliseBody(content, chapter.title());
        } catch (LlmInvocationException exception) {
            // 压缩失败不该毁掉已经写好的全文：保留原章，由调用方继续试下一章。
            log.warn("Trim of '{}' failed: {}", chapter.title(), exception.getMessage());
            return chapter.content();
        }
    }

    /** 去掉空白后的字符数：中文没有词间空格，这比按词切分更接近学生自己数的「字数」。 */
    public static int countWords(String text) {
        if (text == null) {
            return 0;
        }
        return text.replaceAll("\\s", "").length();
    }

    /**
     * 全文的正文字数预算。
     *
     * <p>目标值是用户填的字数上限，实际交付必须落在区间内。这里把区间分成两段来管：</p>
     * <ul>
     *   <li><b>提前</b>：每章拿到的区间按「剩余预算 ÷ 剩余章数」现算，前几章写超了，后面就自动收窄，
     *       而不是每章各自按平均值写、最后一起超；</li>
     *   <li><b>事后</b>：{@code spend} 记录累计字数，供服务端在收尾时决定是否需要压缩。</li>
     * </ul>
     *
     * <p>单章区间收紧到 ±8%（原来 ±15%）：章数少的时候 15% 的浮动就足以让全文超 10%。
     * 同时给出明确下限，避免模型把区间当上限写、越写越少。</p>
     */
    public static final class WordBudget {

        /** 硬上限：用户要求最多超出 10%。 */
        public static final double MAX_OVERSHOOT = 0.10;
        /**
         * 触发压缩的软阈值：达到硬上限的 97% 才开始收。
         *
         * <p>这个值以前是 4%，等于「刚超一点点就整章重写」。而压缩的成本远高于它的收益：
         * 每章一次完整的模型调用（读原文 + 重写全文），实测单章十几秒到两分钟，
         * 三章叠加会把整篇拖到四分钟以上，前端连接先断。既然契约允许超到 110%，
         * 就该把 10% 的余量用起来 —— 落在 110% 以内本来就算合格，不需要为它再花一次改写。</p>
         *
         * <p>取 97% 而不是 100%，是给「压缩本身也会慢半拍、偶尔压不下来」留一点反应时间：
         * 到线才动手的话，一旦第一次改写没收敛就只能硬截断。</p>
         */
        private static final double TRIM_TRIGGER = MAX_OVERSHOOT * 0.97;
        /** 每章允许偏离配额的比例，越小越贴合总值。 */
        private static final double CHAPTER_FLEX = 0.08;
        /** 单章不因「前面写少了」而被摊到过大额度，避免最后一章写成长篇。 */
        private static final double MAX_CHAPTER_FACTOR = 1.5;

        private final int target;
        private final int chapters;
        private int remaining;
        private int remainingChapters;
        private int spent;

        public WordBudget(int target, int chapters) {
            this.target = Math.max(1, target);
            this.chapters = Math.max(1, chapters);
            this.remaining = this.target;
            this.remainingChapters = this.chapters;
        }

        /** One chapter's allowance: the remaining budget shared by the remaining chapters. */
        public Range next() {
            int average = Math.max(1, target / chapters);
            int quota;
            int upper;
            if (remainingChapters <= 1) {
                // 最后一章：用剩下的额度，但不因为前文写少了就无限膨胀，也不再上浮。
                quota = Math.max(1, Math.min(remaining, (int) Math.round(average * MAX_CHAPTER_FACTOR)));
                upper = quota;
            } else {
                quota = Math.max(1, remaining / remainingChapters);
                upper = (int) Math.ceil(quota * (1 + CHAPTER_FLEX));
            }
            int min = Math.max(MIN_CHAPTER_WORDS, (int) Math.floor(quota * (1 - CHAPTER_FLEX)));
            return new Range(min, Math.max(min + 100, upper));
        }

        /** Books what a chapter actually cost and shrinks what is left for the rest. */
        public void spend(int words) {
            spent += Math.max(0, words);
            remaining -= Math.max(0, words);
            remainingChapters = Math.max(0, remainingChapters - 1);
        }

        public int spent() {
            return spent;
        }

        public int target() {
            return target;
        }

        public int chapters() {
            return chapters;
        }

        /** 硬上限（target × 110%）：超过它就是违约。 */
        public int ceiling() {
            return (int) Math.floor(target * (1 + MAX_OVERSHOOT));
        }

        /** 是否值得花一次模型调用去压缩。 */
        public boolean shouldTrim() {
            return spent > (int) Math.floor(target * (1 + TRIM_TRIGGER));
        }

        public record Range(int min, int max) {
        }
    }

    /** Tokens consumed since the last {@link #generate} started; 0 when nothing was written. */
    public int drainTokens() {
        return lastTokens.getAndSet(0);
    }

    /**
     * Validates the credential this generation will spend, without calling the model.
     *
     * <p>Resolving the credential is the whole billing gate: it provisions the caller's relay key,
     * reads the New API account balance（through the throttled snapshot）and rejects a zero balance
     * with {@code 402}. Normally that happens on the first chapter, i.e. after the SSE stream is
     * already open and the progress dialog is on screen — the user then watches a dialog die on
     * chapter one. Running the same check up front lets the client get a plain HTTP error instead,
     * and guarantees nothing is spent before the account is known to be able to pay.</p>
     */
    public void checkBillable(CurrentUser currentUser) {
        credentials(currentUser);
    }

    /** Progress tick, so a 40-second generation does not look frozen. */
    public record Progress(int index, int total, String title) {
    }

    private String writeOne(String key,
                            ProviderCredentials credentials,
                            Brief brief,
                            ThesisOutlineNode chapter,
                            WordBudget.Range range,
                            List<ThesisReferenceService.Reference> references,
                            String digest,
                            boolean thinking) {
        try {
            // Lower than the outline's 0.4: body text is judged on whether it stays on topic.
            String content = complete(key, credentials, List.of(
                    LlmChatMessage.system(systemPrompt(brief)),
                    LlmChatMessage.user(userPrompt(brief, chapter, range, references, digest))),
                    0.3, tokensFor(range.max(), thinking));
            log.info("Chapter '{}' written by '{}': {} chars (quota {}–{})",
                    chapter.title(), key, countWords(content), range.min(), range.max());
            return normaliseBody(content, chapter.title());
        } catch (LlmInvocationException exception) {
            // 重试已经在 complete() 里做过；到这儿就是真的写不动了，交给上层报明是哪一章。
            log.warn("Chapter '{}' failed on '{}': {}", chapter.title(), key, exception.getMessage());
            throw new ChapterGenerationException(chapter.title(), exception);
        }
    }

    /**
     * Writes the data-model/code appendix.
     *
     * <p>Separate from the chapter loop because it is a different kind of text: no argument, no
     * citations, DDL and code instead of prose. Reusing the chapter prompt produced a page of
     * commentary about a database without ever showing a table definition — the model followed the
     * "write continuous prose" instruction and dropped the part the switch exists for.</p>
     */
    private Chapter writeAppendix(String key, ProviderCredentials credentials, Brief brief,
                                  String digest, boolean thinking) {
        try {
            String content = complete(key, credentials, List.of(
                    LlmChatMessage.system(appendixSystemPrompt(brief)),
                    LlmChatMessage.user(appendixPrompt(brief, digest))),
                    // Lowest of all calls: DDL and code are judged on correctness, not variety.
                    0.2, thinking
                            ? Math.min(MAX_OUTPUT_TOKENS, APPENDIX_OUTPUT_TOKENS + THINKING_HEADROOM_TOKENS)
                            : APPENDIX_OUTPUT_TOKENS);
            log.info("Appendix written by '{}': {} chars", key, content.length());
            return new Chapter(APPENDIX_TITLE, 1, normaliseBody(content, APPENDIX_TITLE));
        } catch (LlmInvocationException exception) {
            log.warn("Appendix failed on '{}': {}", key, exception.getMessage());
            throw new ChapterGenerationException(APPENDIX_TITLE, exception);
        }
    }

    /**
     * 从已经写好的正文倒推中英文摘要与关键词。
     *
     * <p><b>为什么必须基于正文，而不是再跑一遍题目。</b>摘要的职责是把这篇论文**实际写了什么**
     * 压缩成一段话；如果只给它题目和表单，它会写出一篇「理想中的论文」的摘要，
     * 与正文的方法、数据、结论对不上 —— 答辩时这是最容易被追问的地方。</p>
     *
     * <p><b>为什么单独一个阶段而不是塞进第一章。</b>此前摘要由模型在第一章里顺手写：
     * 格式时有时无、位置忽前忽后，还常见把 Abstract 混在中文摘要后面、
     * 关键词缺失这类问题。现在提示词要求四种内容用固定标记分隔，服务端解析后存成四个字段，
     * 排版与导出都用结构化数据，不再依赖模型的自律。</p>
     *
     * <p><b>失败不毁正文。</b>正文已经写完、token 也花掉了，摘要调不通只让四项为空，
     * 由调用方告诉用户可以单独重试（{@code POST /documents/{id}/abstract}）。
     * 这与压缩失败的取舍一致：不能因为收尾步骤出问题就丢掉整篇。</p>
     *
     * @param chapters 已写好的正文（含附录、参考文献、致谢之外的章节均可）
     * @return 四项内容；模型不可用或解析失败时返回 {@link Abstract#empty()}
     */
    public Abstract writeAbstract(Brief brief,
                                  String modelKey,
                                  Long siteId,
                                  List<Chapter> chapters,
                                  CurrentUser currentUser) {
        if (chapters == null || chapters.isEmpty()) {
            return Abstract.empty();
        }
        // 摘要要看的是论证本身，附录里的 DDL、参考文献、致谢都不该进入摘要的素材
        List<Chapter> body = chapters.stream()
                .filter(chapter -> !isDataAppendix(chapter.title()))
                .filter(chapter -> !isBackMatter(chapter.title()))
                .toList();
        if (body.isEmpty()) {
            return Abstract.empty();
        }

        ModelUseResolver.Resolved model = modelResolver.resolveOrDefault(
                siteId, modelKey, com.hub.backend.modules.model.entity.LlmModelEntity.TYPE_CHAT);
        String key = model.upstreamKey();
        boolean thinking = model.thinking();
        ProviderCredentials credentials = credentials(currentUser);

        try {
            // 摘要要稳定、可复述，不需要措辞上的变化
            int budget = thinking
                    ? Math.min(MAX_OUTPUT_TOKENS, ABSTRACT_OUTPUT_TOKENS + THINKING_HEADROOM_TOKENS)
                    : ABSTRACT_OUTPUT_TOKENS;
            String content = complete(key, credentials, List.of(
                    LlmChatMessage.system(abstractSystemPrompt(brief)),
                    LlmChatMessage.user(abstractPrompt(brief, body))),
                    0.2, budget);
            Abstract parsed = parseAbstract(content);

            /*
             * 「写完了吗」不能只看有没有内容。
             *
             * 截断时的样子很有迷惑性：中文摘要完整、英文摘要写到一半断在词中间、
             * 【英文关键词】整段没出现 —— parseAbstract 照样能切出三段，isEmpty() 也是 false。
             * 用户拿到的就是一份半句英文 + 空关键词，而界面只会照常显示。
             * 判断依据落在「英文有没有收尾标点」和「英文关键词在不在」这两件客观事实上，
             * 而不是长度阈值（英文本来就长短不一）。
             */
            if (looksTruncated(parsed) && budget < MAX_OUTPUT_TOKENS) {
                int raised = Math.min(MAX_OUTPUT_TOKENS, budget + THINKING_HEADROOM_TOKENS);
                log.warn("Abstract looks truncated on '{}' (enKeywords={}, enTail={}); "
                                + "raising the output budget to {} tokens and retrying once",
                        key, parsed.englishKeywords().isBlank() ? "missing" : "present",
                        tail(parsed.english(), 40), raised);
                content = complete(key, credentials, List.of(
                        LlmChatMessage.system(abstractSystemPrompt(brief)),
                        LlmChatMessage.user(abstractPrompt(brief, body))),
                        0.2, raised);
                parsed = parseAbstract(content);
            }

            if (parsed.isEmpty()) {
                // 上游回了内容但四段标记一个都没认出来：记账照扣，用户拿到的却是空摘要。
                // 单独记一条，排查「明明调用成功了却没有摘要」时才有据可查。
                log.warn("Abstract reply had no recognisable sections on '{}' ({} chars): {}",
                        key, content.length(),
                        content.length() > 120 ? content.substring(0, 120) + "…" : content);
                return parsed;
            }
            if (looksTruncated(parsed)) {
                // 抬过一次预算仍然截断：至少要留下线索，否则只会在「摘要怎么没关键词」里空转。
                log.warn("Abstract still looks truncated on '{}' after the budget was raised "
                                + "(en {} chars, keywords {})",
                        key, parsed.english().length(), parsed.englishKeywords().length());
            }
            log.info("Abstract written by '{}': {} chars (zh), {} chars (en), keywords {}/{}",
                    key, countWords(parsed.chinese()), parsed.english().length(),
                    parsed.chineseKeywords().split("[；;]").length,
                    parsed.englishKeywords().split(";").length);
            return parsed;
        } catch (LlmInvocationException exception) {
            log.warn("Abstract generation failed on '{}': {}", key, exception.getMessage());
            return Abstract.empty();
        }
    }

    /**
     * 摘要看起来是不是被截断了。
     *
     * <p>两条客观判据，都不依赖长度阈值：</p>
     * <ol>
     *   <li><b>英文关键词整段缺失</b> —— 提示词里四段标记是固定的，正常收尾必然有第四段；</li>
     *   <li><b>英文摘要没有收尾标点</b> —— 断流的那一刻通常停在词中间（实测停在
     *       “though the conclusions require”）。</li>
     * </ol>
     *
     * <p>中文摘要不参与判断：它先输出，被截断的永远是后面的英文部分。</p>
     */
    static boolean looksTruncated(Abstract parsed) {
        if (parsed == null || parsed.isEmpty()) {
            // 空摘要走「重试也救不回来」的另一条分支，这里不重复判
            return false;
        }
        String english = parsed.english() == null ? "" : parsed.english().strip();
        String keywords = parsed.englishKeywords() == null ? "" : parsed.englishKeywords().strip();
        if (keywords.isEmpty()) {
            return true;
        }
        if (english.isEmpty()) {
            return false;
        }
        return !english.matches("(?s).*[.!?。！？…\"”’)'）]$");
    }

    /** 日志用的尾串，避免把整段英文摘要灌进一行日志。 */
    private static String tail(String value, int length) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip().replaceAll("\\s+", " ");
        return stripped.length() <= length ? stripped : "…" + stripped.substring(stripped.length() - length);
    }

    /**
     * 解析模型返回的四段式摘要。
     *
     * <p>模型对「用固定标记分隔」的遵守程度明显高于对 JSON 的遵守程度，所以这里让它是
     * 带标记的纯文本，再用标记切分 —— 实测 JSON 会带来引号转义、换行被吃掉、
     * 偶尔干脆包一层 ```json 的问题，而标记式几乎不会失败。</p>
     *
     * <p>容错点（都被真实输出踩过）：
     * <ul>
     *   <li>标记写成中文全角冒号、或前后带空格；</li>
     *   <li>模型自作主张加「摘要：」「Abstract:」前缀，或把关键词写在同一行；</li>
     *   <li>英文关键词用「,」而不是「;」分隔。</li>
     * </ul>
     */
    static Abstract parseAbstract(String raw) {
        if (raw == null || raw.isBlank()) {
            return Abstract.empty();
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        java.util.Map<String, String> slots = splitByMarkers(text);

        return new Abstract(
                cleanChinese(slots.getOrDefault("zh", "")),
                normaliseChineseKeywords(slots.getOrDefault("zhKey", "")),
                cleanEnglish(slots.getOrDefault("en", "")),
                normaliseEnglishKeywords(slots.getOrDefault("enKey", "")));
    }

    /**
     * 一次扫描找出所有标记的位置，按位置把全文切成四段。
     *
     * <p><b>为什么不再「先统一替换成占位符再切」。</b>那条路有两个无法回避的坑，都真实踩到过：</p>
     * <ul>
     *   <li>「摘要」是「中文摘要」的子串、「关键词」是「中文关键词」的子串。逐词替换时，
     *       只要顺序稍有偏差，就会把长标记切成「中文」+ 占位符，摘要开头多出两个字；</li>
     *   <li>更糟的是跨语言误伤：<b>「英文摘要」里也含「摘要」</b>，于是英文摘要一出现就被
     *       判成中文摘要，四段全部错位。</li>
     * </ul>
     *
     * <p>改成扫描后，每个标记只认「最具体的那个名字」（长的优先），且每个位置只归属一次，
     * 错位就不可能发生。段落的边界由「下一个标记的位置」决定，所以模型漏掉某一段时，
     * 那一段是空的，而不是吞掉邻段。</p>
     */
    private static java.util.Map<String, String> splitByMarkers(String text) {
        // 长写法在前：同一位置上「中文关键词」必须先于「关键词」被认领
        String[][] markers = {
                {"zhKey", "【中文关键词】", "[中文关键词]", "中文关键词", "关 键 词"},
                {"enKey", "【英文关键词】", "[英文关键词]", "英文关键词", "Key Words", "Key words", "Keywords"},
                {"zh", "【中文摘要】", "[中文摘要]", "中文摘要", "摘 要"},
                {"en", "【英文摘要】", "[英文摘要]", "英文摘要", "Abstract"},
                // 裸「摘要」「关键词」放最后：它们最容易被正文里的说法误命中
                {"zh", "摘要"},
                {"zhKey", "关键词"},
        };

        // 记下每个被认领的位置：同一起点只保留最具体的那次匹配
        java.util.List<int[]> hits = new ArrayList<>();   // {start, end}
        java.util.List<String> kinds = new ArrayList<>();
        boolean[] taken = new boolean[text.length()];

        for (String[] marker : markers) {
            String kind = marker[0];
            for (int i = 1; i < marker.length; i++) {
                String variant = marker[i];
                int from = 0;
                while (true) {
                    int at = text.indexOf(variant, from);
                    if (at < 0) {
                        break;
                    }
                    from = at + variant.length();
                    if (taken[at]) {
                        // 这个起点已经被更具体的写法认领过了（如「中文摘要」先占了位）
                        continue;
                    }
                    // 标记要紧跟冒号、方括号或换行，才算真标记；
                    // 正文里的「摘要如下：」不是标记，放过它。
                    if (!looksLikeMarker(text, at, variant.length())) {
                        continue;
                    }
                    int end = at + variant.length();
                    for (int p = at; p < end; p++) {
                        taken[p] = true;
                    }
                    hits.add(new int[]{at, end});
                    kinds.add(kind);
                }
            }
        }

        // 按出现位置排序后依次切段
        java.util.List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.comparingInt(i -> hits.get(i)[0]));

        java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
        for (int n = 0; n < order.size(); n++) {
            int index = order.get(n);
            int start = hits.get(index)[1];
            int end = n + 1 < order.size() ? hits.get(order.get(n + 1))[0] : text.length();
            String kind = kinds.get(index);
            // 同一段被重复声明时保留最长的（模型偶尔把中文摘要写两遍）
            String body = text.substring(start, Math.max(start, end)).strip();
            String existing = slots.get(kind);
            if (existing == null || body.length() > existing.length()) {
                slots.put(kind, body);
            }
        }
        return slots;
    }

    /**
     * 判断某个位置的「摘要」「关键词」是不是真的段落标记。
     *
     * <p>带方括号的写法本身就是标记，不需要判断。裸写法则要求后面紧跟冒号或换行 ——
     * 否则正文里的「本文摘要如下所述」「关键词提取是核心步骤」都会被当成段落开头，
     * 把整段正文切进摘要字段。</p>
     */
    private static boolean looksLikeMarker(String text, int at, int length) {
        char first = text.charAt(at);
        if (first == '【' || first == '[') {
            return true;
        }
        int after = at + length;
        while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\u3000')) {
            after++;
        }
        if (after >= text.length()) {
            return true;
        }
        char next = text.charAt(after);
        return next == '：' || next == ':' || next == '\n';
    }

    /** 这个标记是否属于「以冒号收尾」的裸写法，切段时要把冒号一起吃掉。 */
    private static String stripLeadingColon(String body) {
        String out = body;
        while (true) {
            if (out.startsWith("：") || out.startsWith(":") || out.startsWith("\u3000")) {
                out = out.substring(1);
            } else if (out.startsWith(" ")) {
                out = out.substring(1);
            } else {
                return out;
            }
        }
    }

    /** 去掉模型可能加的前缀与多余空白；中文摘要是一段连续文字。 */
    private static String cleanChinese(String value) {
        String cleaned = stripLeadingColon(value == null ? "" : value.strip());
        cleaned = cleaned.replaceAll("^(摘\\s*要)\\s*[:：]\\s*", "");
        return cleaned.replaceAll("\n{2,}", "\n").strip();
    }

    private static String cleanEnglish(String value) {
        String cleaned = stripLeadingColon(value == null ? "" : value.strip());
        cleaned = cleaned.replaceAll("(?i)^abstract\\s*[:：]\\s*", "");
        return cleaned.replaceAll("\n{2,}", "\n").strip();
    }

    /**
     * 中文关键词规范成「以；分隔」。
     *
     * <p>模型三种写法都见过：顿号、逗号、分号。统一成中文分号 —— 这是国内论文模板里
     * 最常见的分隔符，且不会和关键词内部的英文逗号（如「BERT, 预训练」）混淆。</p>
     */
    static String normaliseChineseKeywords(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = stripLeadingColon(value.strip())
                .replaceAll("^(关\\s*键\\s*词|中\\s*文\\s*关\\s*键\\s*词)\\s*[:：]\\s*", "");
        String[] parts = cleaned.split("[；;、,，\\n]+");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String keyword = part.strip().replaceAll("[。.]$", "");
            if (!keyword.isEmpty()) {
                kept.add(keyword);
            }
        }
        return String.join("；", kept);
    }

    /** 英文关键词规范成「以 ; 分隔」，保留词内的连字符与空格（如 machine learning）。 */
    static String normaliseEnglishKeywords(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = stripLeadingColon(value.strip())
                .replaceAll("(?i)^(key\\s*words?|keywords)\\s*[:：]\\s*", "");
        String[] parts = cleaned.split("[;；,，\\n]+");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String keyword = part.strip().replaceAll("[.。]$", "");
            if (!keyword.isEmpty()) {
                kept.add(keyword);
            }
        }
        return String.join("; ", kept);
    }

    /**
     * 摘要阶段的角色设定。
     *
     * <p>刻意不说「你是论文作者」：那样它会用第一人称复述研究动机。摘要的读者是
     * 检索者与评阅人，要的是「问题—方法—结果—结论」四件事，客观陈述、不加评价。</p>
     */
    static String abstractSystemPrompt(Brief brief) {
        PaperTypeProfile profile = PaperTypeProfile.byLabel(brief.paperType());
        return """
                你是中文%s的摘要撰写专家，正在为一份已完成的「%s」补写中英文摘要与关键词。
                你只能依据用户提供的正文写作：正文没写的方法、数据与结论，一律不得出现。
                输出严格遵守用户给的标记格式，不要添加任何解释、寒暄或 Markdown 代码块。
                """.formatted(profile.label(), profile.description()).strip();
    }

    /**
     * 摘要阶段的提示词。
     *
     * <p>正文按章拼进去，但每章都截到 {@code ABSTRACT_SOURCE_CHARS_PER_CHAPTER} 字以内：
     * 摘要只需要知道每章做了什么，不需要逐句原文。全量拼进去会让一次请求的输入达到两万多字，
     * 上游容易超时，而截断后的信息量对写摘要已经足够（实测截到 1200 字/章仍能写准结论）。</p>
     */
    static String abstractPrompt(Brief brief, List<Chapter> chapters) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("论文题目：").append(brief.topic()).append('\n');
        prompt.append("学科专业：").append(nz(brief.discipline())).append(" / ").append(nz(brief.major()))
                .append('\n');
        prompt.append("学历层次：").append(nz(brief.educationLevel())).append('\n');
        prompt.append("\n以下是这篇论文的正文（按章给出，为控制长度已截取每章前半部分）：\n\n");
        for (Chapter chapter : chapters) {
            prompt.append("=== ").append(chapter.title()).append(" ===\n");
            prompt.append(excerpt(chapter.content(), ABSTRACT_SOURCE_CHARS_PER_CHAPTER)).append("\n\n");
        }
        prompt.append("""
                请按下面的格式输出四部分，标记必须原样保留，顺序不可调整：

                【中文摘要】
                一段 300–500 字的连续文字，不分段、不加小标题。依次写清：研究背景与要解决的问题、
                采用的方法或技术路线、主要工作、得出的结论与意义。不要写「本文分为五章」这类结构说明。

                【中文关键词】
                3–5 个，用中文分号「；」分隔，取自论文的核心概念，不要写成一句话。

                【英文摘要】
                Abstract，与中文摘要内容一致，150–300 词，学术英语，不分段。
                术语使用该领域的通行译法。

                【英文关键词】
                3–5 个，与中文关键词一一对应，用英文分号「; 」分隔。

                硬性要求：
                1. 四部分只输出上面这些内容，不要写「摘要如下」「希望对你有帮助」。
                2. 不得编造正文中没有的数据、百分比、样本量或实验结论。
                3. 中文摘要里不要出现「[1]」这类引用编号。
                4. 关键词不要包含「研究」「分析」这类无区分度的词。
                """);
        return prompt.toString().strip();
    }

    /** 摘要素材每章最多取多少字：够看出这一章做了什么，又不至于把整篇塞进请求。 */
    private static final int ABSTRACT_SOURCE_CHARS_PER_CHAPTER = 1200;

    /** 取正文开头的一段，尽量在句号处收尾，避免读到半句话。 */
    private static String excerpt(String content, int limit) {
        if (content == null) {
            return "";
        }
        String text = content.strip();
        if (countWords(text) <= limit) {
            return text;
        }
        String head = text.substring(0, Math.min(text.length(), limit * 2));
        int cut = Math.max(head.lastIndexOf('。'), Math.max(head.lastIndexOf('！'), head.lastIndexOf('？')));
        return (cut > limit / 2 ? head.substring(0, cut + 1) : head).strip() + "…";
    }

    /**
     * One streaming model call, retried on transient failures.
     *
     * <p>Streaming rather than {@code complete()}: the upstream rejects non-stream requests on some
     * channel groups ("请使用 stream=true", HTTP 400), which otherwise loses a whole chapter at
     * random. Token usage is accumulated into {@link #lastTokens} here so every call path — chapter
     * or appendix — is billed into the same total.</p>
     *
     * <p><b>重试只针对「这一次运气不好」。</b>中继网关偶发 429、5xx、连接重置，或者把流式转非流式
     * 时把正文丢掉 —— 不重试的话，一章失败整篇作废，前面几章的钱白花。余额不足、密钥无效、
     * 模型下线这类确定性失败不重试：再等一遍只会让用户多等十几秒才看到同一句话，
     * 判定交给 {@link LlmFailures}。</p>
     */
    private String complete(String key, ProviderCredentials credentials,
                            List<LlmChatMessage> messages, double temperature, int maxTokens) {
        int budget = maxTokens;
        for (int attempt = 1; ; attempt++) {
            try {
                return completeOnce(key, credentials, messages, temperature, budget);
            } catch (LlmInvocationException exception) {
                if (attempt >= MAX_ATTEMPTS || !LlmFailures.isRetryable(exception)) {
                    throw exception;
                }
                /*
                 * 空响应且预算还没顶格时，把预算抬上去再试。
                 *
                 * 推理模型把思考和正文算在同一个 max_tokens 里：给 1536 时它可能把整份预算
                 * 花在推演上，正文一个字都没输出，而网关侧 completion_tokens 正好打满、
                 * 记账成功 —— 后端收到空 content，用户看到「第二章模型调用失败」。
                 * 这类失败重试同样的预算几乎必然复现，加钱才是有效重试。
                 */
                if (isEmptyResponse(exception) && budget < MAX_OUTPUT_TOKENS) {
                    budget = Math.min(MAX_OUTPUT_TOKENS, budget + THINKING_HEADROOM_TOKENS);
                    log.warn("Model returned empty content on '{}' (attempt {}/{}); "
                                    + "raising the output budget to {} tokens and retrying",
                            key, attempt, MAX_ATTEMPTS, budget);
                    continue;
                }
                long waitMs = RETRY_BACKOFF_MS * attempt;
                log.warn("Model call failed on '{}' (attempt {}/{}): {} — retrying in {}ms",
                        key, attempt, MAX_ATTEMPTS, exception.getMessage(), waitMs);
                sleepBeforeRetry(waitMs);
            }
        }
    }

    /** 上游回了 200 但正文为空 —— 通常意味着输出预算被思考过程吃光了。 */
    private static boolean isEmptyResponse(LlmInvocationException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("empty response");
    }

    private String completeOnce(String key, ProviderCredentials credentials,
                                List<LlmChatMessage> messages, double temperature, int maxTokens) {
        LlmChatCommand command = new LlmChatCommand(key, messages, temperature, maxTokens, credentials);
        StringBuilder buffer = new StringBuilder();
        LlmUsage[] usage = new LlmUsage[1];
        llmClient.stream(command, chunk -> {
            if (chunk.hasContent()) {
                buffer.append(chunk.contentDelta());
            }
            if (chunk.usage() != null) {
                usage[0] = chunk.usage();
            }
        });
        String content = buffer.toString().strip();
        if (content.isEmpty()) {
            throw new LlmInvocationException("model returned an empty response");
        }
        lastTokens.addAndGet(usage[0] == null ? 0 : usage[0].totalTokensOrZero());
        return content;
    }

    /**
     * 按本章字数上限推算单次请求的输出上限。
     *
     * <p>中文正文大约 1 字 ≈ 1 token，再留三成余量防止模型展开时被截断；下界 {@link #MIN_OUTPUT_TOKENS}
     * 保证短章也能写完一段，上界 {@link #MAX_OUTPUT_TOKENS}。</p>
     *
     * <p><b>推理模型的余量加在估算值上，而不是加在已夹取的结果上。</b>这一点很关键：
     * 若先把字数需求夹到下界（2048）再加余量，等于给短章也按 2048 的正文 + 2000 的思考
     * 预冻，浪费；若加在估算值上，短章的预算是「真实需要 + 思考」，更贴近实际。</p>
     *
     * @param thinking 该模型是否把推理过程也算进同一个输出预算（{@code llm_models.is_thinking}）；
     *                 是则额外留 {@link #THINKING_HEADROOM_TOKENS}，否则思考会挤掉正文
     */
    static int tokensFor(int maxWords, boolean thinking) {
        int forBody = (int) Math.ceil(Math.max(1, maxWords) * 1.3) + 256;
        if (thinking) {
            // 思考与正文共用预算，两者相加后再统一夹上下界
            forBody += THINKING_HEADROOM_TOKENS;
        }
        return Math.max(MIN_OUTPUT_TOKENS, Math.min(MAX_OUTPUT_TOKENS, forBody));
    }

    /** 兼容旧调用：默认按非推理模型算。 */
    static int tokensFor(int maxWords) {
        return tokensFor(maxWords, false);
    }

    /**
     * 压缩一章的预算。
     *
     * <p>输出长度由 {@code target} 决定（只删减不改写），但压缩同样要「读懂原文再删」，
     * 思考开销与写正文是一个量级，所以照常叠加 {@link #THINKING_HEADROOM_TOKENS}。
     * 由于 target 必然小于原文字数，总额度仍低于「按原文算」，不会把压缩拖慢。</p>
     */
    static int trimTokensFor(int target) {
        return tokensFor(target, true);
    }

    private static void sleepBeforeRetry(long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Thrown with the chapter title so the client can say which part needs a retry. */
    public static class ChapterGenerationException extends RuntimeException {
        private final String chapterTitle;

        public ChapterGenerationException(String chapterTitle, Throwable cause) {
            super("生成「" + chapterTitle + "」时失败："
                    + UpstreamFailureText.describe(
                            cause == null ? null : cause.getMessage(),
                            null,
                            UpstreamFailureText.GENERIC_TEXT),
                    cause);
            this.chapterTitle = chapterTitle;
        }

        public String chapterTitle() {
            return chapterTitle;
        }
    }

    private ProviderCredentials credentials(CurrentUser currentUser) {
        if (newApiProperties.isEnabled() && currentUser == null) {
            // 按 token 计费必须有一个可扣费的账户：游客没有中继令牌，直接说明要登录，
            // 而不是抛一个只有日志能看懂的内部状态错误。
            throw new UnauthorizedException(ApiMessages.LOGIN_REQUIRED);
        }
        // 统一交给凭据解析器：开中继时是「该用户自己的令牌 + 余额校验」，
        // 关闭中继（本地开发）时是静态 key —— 后者缺 key 会在这里统一报 503，
        // 不必等到第一次模型调用才发现没配。
        return relayCredentials.resolve(currentUser);
    }

    /** Headings that close the document and are assembled rather than written. */
    private static boolean isBackMatter(String title) {
        return title != null && (title.contains("参考文献") || title.contains("致谢"));
    }

    /** The appendix the 「数据模型与代码」 switch adds; matched loosely because users rename it. */
    static boolean isDataAppendix(String title) {
        return title != null
                && title.contains("附录")
                && (title.contains("代码") || title.contains("数据模型"));
    }

    /** Keeps the next chapter oriented without spending tokens on the previous chapter's prose. */
    private static String shortDigest(Chapter chapter) {
        String plain = chapter.content().replaceAll("\\s+", " ").strip();
        String head = plain.length() <= 140 ? plain : plain.substring(0, 140) + "…";
        return "- " + chapter.title() + "：" + head;
    }

    static String systemPrompt(Brief brief) {
        PaperTypeProfile profile = PaperTypeProfile.byLabel(brief.paperType());
        // Not every type is a degree thesis: 「教学设计」is a lesson plan and 「职业生涯规划书」is
        // written in the first person. Telling the model it is a "学位论文写作专家" made all of them
        // come back as academic prose, so the role line follows the type.
        StringBuilder rules = new StringBuilder("""
                你是中文%s写作专家，正在为一份「%s」逐章撰写正文。

                硬性要求：
                1. 只用中文写作，不使用口语、表情、口号式排比。
                2. 不要重复标题本身，直接从正文写起；不要写「本章将……」这类空话开场。
                3. 不要编造具体数据、问卷份数、访谈对象、实验结果。需要举例时明确写成示例或说明尚待调研。
                4. 不要输出 markdown 代码块、不要输出「#」标题、不要输出项目符号列表标题。段落之间用空行分隔。
                5. 三级标题如果大纲给了，用「1.1」这样的编号自然穿插在正文中。
                """.formatted(profile.label(), profile.description()));

        if (brief.dataModelEnabled()) {
            // 例外只对代码开放：正文仍然是论文体，附录才是清单式内容。
            // 不加这一条，「数据模型与代码」永远只剩一段关于数据库的散文 —— 第 4 条把代码块禁掉了。
            rules.append("6. 例外：附录中的 SQL / 程序代码必须用 ``` 包裹并标注语言（sql / java / python 等），"
                    + "代码要能直接运行或只需替换 TODO 标注的配置值；附录之外的正文仍不得出现代码块。\n");
        }

        rules.append("\n本篇论文的设定（必须遵守）：\n");
        rules.append("- 题目：").append(brief.topic()).append('\n');
        rules.append("- 学历层次：").append(nz(brief.educationLevel()))
                .append("（据此决定深度：本科重系统实现与工作量，硕士重假设检验与实证，博士重理论贡献）\n");
        // The type drives wording, not just a label: a lesson plan must read like a lesson plan.
        rules.append("- 文本类型：").append(profile.label())
                .append("（").append(profile.description()).append("）\n");
        rules.append("- 本类型的写作风格（必须遵守）：").append(profile.writingGuide()).append('\n');
        rules.append("- 学科专业：").append(nz(brief.discipline())).append(" / ").append(nz(brief.major())).append('\n');
        rules.append("- 大纲层级：").append(brief.outlineDepth() == 2 ? "二级（只到节，不再细分目）" : "三级（章—节—目）").append('\n');
        rules.append("- 全文目标字数：").append(brief.targetWords())
                .append(" 字。这是全篇加总的配额，每章会给出本章的目标字数，")
                .append("按章配额写、不要平均分配也不要整体加码，各章相加贴近该数即可\n");
        rules.append("- 文献引用：").append(brief.referenceEnabled() ? "已开启，必须使用提供的文献编号引用" : "未开启，不要标注任何引用编号").append('\n');
        rules.append("- 附件资料：").append(brief.attachmentsEnabled() ? "用户会提供参考资料，需与之呼应的部分要呼应" : "无").append('\n');
        rules.append("- 数据模型：").append(brief.dataModelEnabled()
                ? "开启，需给出数据模型设计（实体关系与数据表结构；实证类论文则为变量定义与量表结构）"
                        + "和关键代码实现，代码集中在附录，正文只做设计说明"
                : "关闭，不要引入实证假设检验框架，也不要出现数据表定义或任何代码").append('\n');

        return rules.toString().strip();
    }

    /**
     * Role line for the appendix.
     *
     * <p>Deliberately not the thesis-writer persona: asked to "write a chapter", the model produces
     * commentary. Asked to "hand over the schema and the code", it hands over DDL and code.</p>
     */
    static String appendixSystemPrompt(Brief brief) {
        return """
                你是%s方向的系统实现与数据库设计工程师，正在为一篇中文学位论文撰写附录。
                附录的读者是答辩老师：他们要看到可核对的数据结构和可运行的代码，而不是评论。
                你的输出会被直接排进论文，所以只输出内容本身，不要写「以下是」「希望对你有帮助」这类话。
                """.formatted(nz(brief.major())).strip();
    }

    /**
     * Requirements for the appendix body.
     *
     * <p>The three parts mirror what a 系统实现类 thesis is graded on: the schema (does the data
     * model support the stated functions), the code (is there real work behind the claim), and the
     * environment note (can somebody reproduce it). 实证类论文 gets the same three slots with
     * 变量表 / 量表 replacing 表结构.</p>
     */
    static String appendixPrompt(Brief brief, String digest) {
        PaperTypeProfile profile = PaperTypeProfile.byLabel(brief.paperType());
        StringBuilder prompt = new StringBuilder();
        prompt.append("请撰写《").append(brief.topic()).append("》的「").append(APPENDIX_TITLE).append("」。\n\n");
        prompt.append("论文信息：").append(profile.label()).append("（").append(profile.description()).append("）· ")
                .append(nz(brief.educationLevel())).append(" · ")
                .append(nz(brief.discipline())).append(" / ").append(nz(brief.major())).append('\n');
        if (!digest.isBlank()) {
            prompt.append("\n正文已写的内容（附录需与之一致，不要另起一套实体或变量）：\n").append(digest).append('\n');
        }

        prompt.append("""

                按下面三部分输出，用「一、」「二、」「三、」各起一段，不要输出「#」标题：

                一、数据模型设计
                先说明核心实体及其关系（系统实现类），或变量定义与测量方式（实证类）。
                然后给出数据表结构：每张表用一段 SQL 建表语句（MySQL 方言），字段必须带中文注释；
                表名与字段名用小写英文加下划线。若为实证类，改用「变量名 | 含义 | 测量方式 | 数据来源」的纯文本行给出变量表，
                并给出问卷或量表的题项结构。

                二、关键代码实现
                给出 2-3 段核心代码，语言按学科选择（计算机类用 Java 或 Python 配 SQL，经管类用 Python / Stata / R 配 SQL）。
                必须是可运行的骨架：有类名或函数签名、关键逻辑与中文注释；
                数据库连接、密钥、文件路径、模型名等需要按环境替换的值用 TODO 注释标出。
                每段代码前用一句话说明它对应论文哪一章的哪个功能。

                三、实现说明
                用 150-300 字说明运行环境（语言与依赖版本）、数据初始化步骤，以及这套实现与正文的对应关系。

                硬性要求：
                1. SQL 与程序代码必须用 ``` 包裹并标注语言，代码之外不要使用代码块。
                2. 不要输出 markdown 表格与「#」标题；表格用「字段名 | 类型 | 说明」这样的纯文本行表达。
                3. 不得编造运行结果、性能数字或测试结论；没有实测就写明「尚未实测」。
                4. 不要重复正文已经论证过的内容，这一节只放结构、代码和运行说明。
                """);
        return prompt.toString().strip();
    }

    static String userPrompt(Brief brief,
                                     ThesisOutlineNode chapter,
                                     WordBudget.Range range,
                                     List<ThesisReferenceService.Reference> references,
                                     String digest) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请撰写以下这一章的完整正文：\n\n");
        prompt.append("章标题：").append(chapter.title()).append('\n');

        if (chapter.children() != null && !chapter.children().isEmpty()) {
            prompt.append("本章小节结构（每个小节都必须写到，顺序不可调整）：\n");
            for (ThesisOutlineNode section : chapter.children()) {
                prompt.append("  ").append(section.title()).append('\n');
                for (ThesisOutlineNode sub : section.children() == null ? List.<ThesisOutlineNode>of() : section.children()) {
                    prompt.append("    - ").append(sub.title()).append('\n');
                }
            }
            // Numbering is applied downstream from the outline. Letting the model number its own
            // headings produced duplicated ones ("3.1 数据来源与采集方法" inside a chapter whose
            // outline already carries that title) and occasionally dropped a section entirely.
            prompt.append("\n排版约定：\n");
            prompt.append("1. 正文直接开始论述，不要重复输出章标题。\n");
            prompt.append("2. 小节标题照抄上方结构，但不要自己加「1.1」「3.2」这类编号前缀。\n");
            // 三级标题以前只有「照抄结构」这一条约束，模型于是把「技术可行性」并进正文首句
            // 写成「技术可行性。系统面向……」。导出时按整行匹配标题，这一行既不是纯标题
            // 也认不出编号，51 个三级标题只有 6 个进了 Word 目录。独占一行是硬要求。
            prompt.append("3. **每一条标题都必须单独占一行**：上方缩进两格的小节标题、以及带「-」的")
                    .append("下一级小标题，一个不落。标题行不加句号、冒号，后面不接任何解释；")
                    .append("正文从下一行重新开始。\n");
            prompt.append("4. 绝对不要写成「技术可行性。系统面向……」这种标题与正文挤在同一行的形式。\n");
            prompt.append("5. 上方列出的每个小节都要有实质内容，不得遗漏或合并。\n");
        }

        // 只写"不少于 X、不超过 Y"时，模型把下限当目标、再顺手多写两三成，几乎必然超上限，
        // 然后触发整章重写压缩 —— 那是每章一次完整的模型调用，实测单章压缩要十几秒到两分钟。
        // 所以这里换三件事：
        //  1. 先给一个**单一目标数**（不写下限），模型对单个数字的命中率明显高于对区间；
        //  2. 上限写成硬的、且放在前面，并明确"到此为止，不要继续展开"；
        //  3. 有多个小节时给出**逐节字数**，让模型按配额分配而不是自由发挥。
        // 分母仍是"剩余预算 ÷ 剩余章数"（见 WordBudget），所以前面写超了后面会自动收窄。
        int chapterTarget = (int) Math.round((range.min() + range.max()) / 2.0);
        prompt.append("\n本章篇幅：目标 ").append(chapterTarget)
                .append(" 字，硬上限 ").append(range.max())
                .append(" 字。\n");
        prompt.append("这是按全文目标字数倒推出来的配额，请贴着目标字数写：写到目标就收尾，")
                .append("不要为了充实而扩写、不要用同义反复凑字数。超过硬上限的部分会被系统删减改写。\n");

        int sections = chapter.children() == null ? 0 : chapter.children().size();
        if (sections >= 2) {
            int perSection = Math.max(80, chapterTarget / sections);
            prompt.append("各小节篇幅：共 ").append(sections).append(" 个小节，每节约 ")
                    .append(perSection).append(" 字，合计约 ").append(perSection * sections)
                    .append(" 字。某一节写长了就从其余小节里省出来，总量不要超。\n");
        }

        if (!references.isEmpty() && brief.referenceEnabled()) {
            prompt.append("\n可引用的文献（仅限下列条目，用 [编号] 标注出处，不要编造其它文献）：\n");
            int n = 1;
            for (ThesisReferenceService.Reference reference : references) {
                prompt.append('[').append(n++).append("] ").append(reference.citation()).append('\n');
            }
        } else if (brief.referenceEnabled()) {
            prompt.append("\n（文献索引本次不可用，正文中不要出现任何引用编号。）\n");
        }

        if (brief.material() != null && !brief.material().isBlank()) {
            String trimmed = brief.material().strip();
            prompt.append("\n用户提供的参考资料（节选，写作时需与之呼应）：\n")
                    .append(trimmed.length() > 1500 ? trimmed.substring(0, 1500) + "…" : trimmed)
                    .append('\n');
        }

        if (!digest.isBlank()) {
            prompt.append("\n前几章已写内容摘要（避免重复，注意衔接）：\n").append(digest).append('\n');
        }

        prompt.append("\n现在直接输出本章正文，不要任何前言或解释。");
        return prompt.toString();
    }

    /**
     * Trims the decorations models add despite being told not to.
     *
     * <p>Removes a leading restatement of the chapter title, an answer wrapped in one markdown fence
     * and any lone {@code #} heading lines, because the client already renders the heading itself.
     * Lines inside a code fence are left untouched: a Python comment starts with {@code #}, and
     * dropping it would silently delete the explanation from every code listing in the appendix.</p>
     */
    static String normaliseBody(String content, String chapterTitle) {
        String body = stripWrappingFence(content.strip());

        List<String> kept = new ArrayList<>();
        boolean inCode = false;
        for (String line : body.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                inCode = !inCode;
                kept.add(line);
                continue;
            }
            // A line that is just a heading or the chapter title is redundant — outside code only.
            if (!inCode && stripped.startsWith("#")) {
                continue;
            }
            if (!inCode && !stripped.isEmpty() && sameHeading(stripped, chapterTitle)) {
                continue;
            }
            kept.add(line);
        }
        return String.join("\n", kept).strip();
    }

    /** Opens and closes the whole answer; two fence lines mean "wrapped", not "contains code". */
    private static String stripWrappingFence(String body) {
        if (!body.startsWith("```")) {
            return body;
        }
        long fences = body.lines().filter(line -> line.strip().startsWith("```")).count();
        int firstBreak = body.indexOf('\n');
        int lastFence = body.lastIndexOf("```");
        if (fences != 2 || firstBreak < 0 || lastFence <= firstBreak) {
            return body;
        }
        return body.substring(firstBreak + 1, lastFence).strip();
    }

    private static boolean sameHeading(String line, String chapterTitle) {
        String a = line.replaceAll("[\\s\\p{Punct}（）()：:。.]", "");
        String b = chapterTitle == null ? "" : chapterTitle.replaceAll("[\\s\\p{Punct}（）()：:。.]", "");
        return !b.isEmpty() && a.equals(b);
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "未指定" : value;
    }
}
