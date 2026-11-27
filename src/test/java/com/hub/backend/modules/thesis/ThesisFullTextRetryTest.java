package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 全文生成的「调用失败」与「输出上限」两条规则。
 *
 * <p>两个问题都源自同一处：每次请求都按固定的输出上限让上游预冻额度，
 * 而且一次失败就整篇作废。这里锁住修复后的行为：</p>
 *
 * <ul>
 *   <li><b>瞬时失败要重试</b>：429 / 5xx / 连接重置重试一次就能继续，用户不必重跑整篇；</li>
 *   <li><b>余额不足不重试</b>：再等两轮只会让用户多等十几秒才看到同一句话；</li>
 *   <li><b>输出上限跟着本章字数走</b>：短章不再按上界冻结，余额不宽裕也能跑完。</li>
 * </ul>
 */
class ThesisFullTextRetryTest {

    private static final CurrentUser USER = new CurrentUser(42L, 1L, "demo");

    private final LlmProperties llmProperties = new LlmProperties();
    private final NewApiProperties newApiProperties = new NewApiProperties();
    private final UserGatewayTokenService tokens = mock(UserGatewayTokenService.class);
    private final UserRelayCredentialResolver relayCredentials =
            new UserRelayCredentialResolver(
                    tokens,
                    mock(com.hub.backend.modules.gateway.NewApiGroupTokenService.class),
                    mock(com.hub.backend.modules.model.ModelGroupRouter.class),
                    llmProperties,
                    newApiProperties);

    /** 本类不关心路由：给一个「没有目录行就走原样 key」的解析器即可。 */
    private static com.hub.backend.modules.model.ModelUseResolver modelResolver() {
        return new com.hub.backend.modules.model.ModelUseResolver(
                mock(com.hub.backend.modules.model.repository.LlmModelRepository.class),
                new ObjectMapper(),
                mock(com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository.class),
                llmPropertiesInstance());
    }

    private static LlmProperties llmPropertiesInstance() {
        return new LlmProperties();
    }

    private static ThesisFullTextGenerator.Brief brief(int targetWords) {
        return new ThesisFullTextGenerator.Brief(
                "基于大语言模型的金融舆情分析系统", "本科在读", "论文助手", "工学",
                "计算机科学与技术", 2, targetWords, false, false, false, "");
    }

    private static ThesisOutlineNode chapter(String title) {
        return new ThesisOutlineNode("n-" + title, title, 1, List.of());
    }

    /** 脚本化的 LlmClient：按顺序吐出预设结果（要么抛异常，要么返回正文）。 */
    private static final class ScriptedClient implements LlmClient {
        private final List<Object> script;
        final List<LlmChatCommand> commands = new ArrayList<>();

        ScriptedClient(List<Object> script) {
            this.script = new ArrayList<>(script);
        }

        @Override
        public com.hub.backend.infra.llm.dto.LlmChatCompletion complete(LlmChatCommand command) {
            throw new UnsupportedOperationException("全文生成只走流式");
        }

        @Override
        public void stream(LlmChatCommand command, Consumer<LlmStreamChunk> onChunk) {
            commands.add(command);
            Object step = script.size() > 1 ? script.remove(0) : script.get(0);
            if (step instanceof LlmInvocationException failure) {
                throw failure;
            }
            onChunk.accept(new LlmStreamChunk(null, "第一章 绪论\n\n" + step, null, null, false,
                    LlmUsage.empty(), List.of()));
        }
    }

    private List<ThesisFullTextGenerator.Chapter> run(ScriptedClient client, int targetWords) {
        ThesisFullTextGenerator generator = new ThesisFullTextGenerator(
                client, llmProperties, newApiProperties, relayCredentials, modelResolver());
        return generator.generate(brief(targetWords), "test-model", null,
                List.of(chapter("第一章 绪论")), List.of(), USER, progress -> { }).chapters();
    }

    @Test
    void transientFailureIsRetriedAndTheChapterStillCompletes() {
        String body = "正文".repeat(400);
        ScriptedClient client = new ScriptedClient(List.of(
                new LlmInvocationException("Model gateway returned HTTP 503: service unavailable"),
                body));

        List<ThesisFullTextGenerator.Chapter> written = run(client, 4000);

        assertEquals(1, written.size());
        assertEquals(2, client.commands.size(), "503 之后应当自动重试一次");
        assertTrue(written.get(0).content().contains("正文"));
    }

    @Test
    void rateLimitIsRetried() {
        ScriptedClient client = new ScriptedClient(List.of(
                new LlmInvocationException("模型服务当前限流（HTTP 429），请等十几秒后重试"),
                "正文".repeat(300)));

        assertEquals(1, run(client, 4000).size());
        assertEquals(2, client.commands.size());
    }

    @Test
    void quotaFailureStopsAfterOneCall() {
        String quota = "模型账户余额不足，本次请求未能执行（HTTP 403）。预冻额度按单次请求的最大输出量计算";
        ScriptedClient client = new ScriptedClient(List.of(new LlmInvocationException(quota)));

        ThesisFullTextGenerator.ChapterGenerationException exception =
                assertThrows(ThesisFullTextGenerator.ChapterGenerationException.class,
                        () -> run(client, 4000));

        assertTrue(exception.getMessage().contains("第五章") || exception.getMessage().contains("第一章"));
        assertTrue(exception.getMessage().contains("余额不足"));
        assertEquals(1, client.commands.size(), "余额不足不该重试：再等一遍还是同一句话");
    }

    @Test
    void retriesAtMostFourTimes() {
        ScriptedClient client = new ScriptedClient(List.of(
                new LlmInvocationException("Model gateway stream failed: Connection reset")));

        assertThrows(ThesisFullTextGenerator.ChapterGenerationException.class,
                () -> run(client, 4000));

        // 4 次是为了配合「空响应逐级抬预算」：每次 +2560，最多试到顶格
        assertEquals(4, client.commands.size(), "总共最多试四次，不能无限重试");
    }

    @Test
    void outputLimitFollowsTheChapterBudgetInsteadOfAlwaysUsingTheMaximum() {
        // 6000 字 / 4 章 → 每章约 1500 字，对应 tokens ≈ 1500×1.3 + 256，明显低于上界
        ScriptedClient client = new ScriptedClient(List.of("正文".repeat(300)));
        ThesisFullTextGenerator generator = new ThesisFullTextGenerator(
                client, llmProperties, newApiProperties, relayCredentials, modelResolver());
        generator.generate(brief(6000), "test-model", null,
                List.of(chapter("第一章 绪论"), chapter("第二章 文献综述"),
                        chapter("第三章 研究设计"), chapter("第四章 实证分析")),
                List.of(), USER, progress -> { });

        Integer maxTokens = client.commands.get(0).maxTokens();
        assertTrue(maxTokens != null && maxTokens < 8192,
                "短章不该按上界冻结额度，实际是 " + maxTokens);
        assertTrue(maxTokens >= 3072, "至少要容得下本章字数区间，实际是 " + maxTokens);
    }

    @Test
    void tokensForScalesWithTheChapterAndStaysWithinBounds() {
        // 一篇文章被切成很多短章时，仍然给足最小输出空间，不能交付截肢的正文
        assertEquals(3072, ThesisFullTextGenerator.tokensFor(100), "短章取下界");
        // 5000 字的章：5000×1.3+256 = 6756，未及上界，按需给额
        assertEquals(6756, ThesisFullTextGenerator.tokensFor(5000));
        // 超出上界需求的章才顶格
        assertEquals(8192, ThesisFullTextGenerator.tokensFor(8000));
        assertTrue(ThesisFullTextGenerator.tokensFor(1200) < 3100);
    }

    // ---------------------------------------------------------------------------------------
    // 收尾阶段（把超配的章节收回目标字数）要给用户一条进度，否则进度条停在最后一格不动，
    // 看起来就是卡死了。文案只说「最终整合中」，不说压缩到多少字。
    // ---------------------------------------------------------------------------------------

    @Test
    void announcesTheFinalPolishingStageWhenChaptersNeedTrimming() {
        // 目标 1000 字、单章却写了约 3000 字 → 远超 4% 阈值，触发收尾压缩。
        String longBody = "正文".repeat(1500);
        ScriptedClient client = new ScriptedClient(List.of(longBody));

        List<ThesisFullTextGenerator.Progress> seen = new ArrayList<>();
        ThesisFullTextGenerator generator = new ThesisFullTextGenerator(
                client, llmProperties, newApiProperties, relayCredentials, modelResolver());
        generator.generate(brief(1000), "test-model", null,
                List.of(chapter("第一章 绪论")), List.of(), USER, seen::add);

        ThesisFullTextGenerator.Progress last = seen.get(seen.size() - 1);
        assertEquals(ThesisFullTextGenerator.FINAL_POLISHING_TITLE, last.title(),
                "收尾阶段必须有一条自己的进度，实际序列：" + seen.stream()
                        .map(ThesisFullTextGenerator.Progress::title).toList());
        assertEquals(last.total(), last.index(),
                "index 必须等于 total：进度条这时已经满了，不该再往前一格");
    }

    @Test
    void aTrimThatDropsTheChapterToASummaryIsRejected() {
        // 实测故障：1602 字的章节要求压到 1234 字，模型却回了一段 84 字的摘要，
        // 旧代码只判「变得更短了」就接受 —— 正文被整段丢掉，用户拿到 84 字的第一章。
        String longBody = "正文".repeat(800);
        String summary = "本章概述了研究背景与意义。";
        ScriptedClient client = new ScriptedClient(List.of(longBody, summary));

        List<ThesisFullTextGenerator.Chapter> written = run(client, 1600);

        assertEquals(1, written.size());
        int chars = ThesisFullTextGenerator.countWords(written.get(0).content());
        assertTrue(chars >= 400,
                "压缩过头必须被拒绝、保留原章，实际只剩 " + chars + " 字");
        assertTrue(written.get(0).content().contains("正文"), "保留的应当是原来的正文");
    }

    // ---------------------------------------------------------------------------------------
    // 推理模型（is_thinking）把「思考」和「正文」算在同一个 max_tokens 里。
    // 预算不够时它会把整份预算花在推演上，正文一个字都不写 —— 网关 completion_tokens
    // 正好打满、记账成功，后端收到空 content，现象就是「写第 N 章时模型调用失败」。
    // ---------------------------------------------------------------------------------------

    @Test
    void thinkingModelsGetExtraOutputBudget() {
        // 短章：普通模型被下界兜到 3072；推理模型是「真实需要 776 + 思考 2560」= 3336。
        // 差值会被下界吸收，所以断言的是「推理模型的总预算确实多出思考那一份」，
        // 而不是简单比大小 —— 否则会误判这个设计。
        assertEquals(3072, ThesisFullTextGenerator.tokensFor(400, false));
        assertEquals(3336, ThesisFullTextGenerator.tokensFor(400, true));

        // 长章：推理模型顶到上界
        assertTrue(ThesisFullTextGenerator.tokensFor(5000, true)
                > ThesisFullTextGenerator.tokensFor(5000, false));
        assertEquals(8192, ThesisFullTextGenerator.tokensFor(5000, true));
    }

    // ---------------------------------------------------------------------------------------
    // 「生成时就写准字数」：目标不是靠事后压缩凑出来的，而是写的时候就说清楚。
    // 压缩是每章一次完整的模型调用（读原文 + 重写全文），实测单章十几秒到两分钟，
    // 是整篇慢的主要原因 —— 能一开始写对，就不该走到压缩这一步。
    // ---------------------------------------------------------------------------------------

    @Test
    void theChapterPromptAsksForASingleTargetAndSplitsItAcrossSections() {
        // 两个关键改法：
        //  1. 给单一目标数，而不是「不少于 A、不超过 B」的区间（模型会把 A 当目标再往上冲）；
        //  2. 多个小节时给出逐节配额，模型按配额分配而不是自由发挥。
        ThesisOutlineNode withSections = new ThesisOutlineNode("n1", "第一章 绪论", 1,
                List.of(
                        new ThesisOutlineNode("n1-1", "研究背景", 2, List.of()),
                        new ThesisOutlineNode("n1-2", "研究意义", 2, List.of()),
                        new ThesisOutlineNode("n1-3", "国内外研究现状", 2, List.of())));

        String prompt = ThesisFullTextGenerator.userPrompt(
                brief(3000), withSections, new ThesisFullTextGenerator.WordBudget.Range(800, 1000),
                List.of(), "");

        assertTrue(prompt.contains("目标 900 字"),
                "应当给出单一目标数（区间中值），实际提示词：" + prompt);
        assertTrue(prompt.contains("硬上限 1000 字"), "上限要写明，且是硬约束：" + prompt);
        assertFalse(prompt.contains("不少于 800 字"),
                "不要再用「不少于 X」：模型会把它当成写作目标，一路往上冲");
        // 三个小节、目标 900 字 → 每节 300 字
        assertTrue(prompt.contains("每节约 300 字"),
                "多小节时要给出逐节配额，否则模型会按自己节奏把总量写超：" + prompt);
    }

    @Test
    void aSingleSectionChapterSkipsThePerSectionSplit() {
        // 只有一节时逐节配额没有意义（等于把目标再说一遍），不该出现
        ThesisOutlineNode oneSection = new ThesisOutlineNode("n1", "第一章 绪论", 1,
                List.of(new ThesisOutlineNode("n1-1", "研究背景", 2, List.of())));

        String prompt = ThesisFullTextGenerator.userPrompt(
                brief(3000), oneSection, new ThesisFullTextGenerator.WordBudget.Range(800, 1000),
                List.of(), "");

        assertFalse(prompt.contains("每节约"), "单节章节不该再拆配额：" + prompt);
    }

    @Test
    void aSmallOvershootIsCutInsteadOfRewritten() {
        // 4000 字 / 1 章、写了约 3920 字本就在限内；这里让脚本返回更长的正文，
        // 轻微超出时不该为它调一次改写。改写要读完整章再重写整章，实测单章 14–25 秒；
        // 截掉一两句更划算，也不会有压成摘要的风险。
        //
        // 断言方式：脚本只准备了一次「写正文」的返回，没有准备改写用的返回。
        // 一旦代码去调模型压缩，ScriptedClient 会因为没有下一个脚本而抛异常。
        String longBody = "这是正文句子。".repeat(560); // 约 3920 字
        ScriptedClient client = new ScriptedClient(List.of(longBody));

        List<ThesisFullTextGenerator.Chapter> written = run(client, 4000);

        assertEquals(1, client.commands.size(),
                "轻微超出不该调模型改写，实际调了 " + client.commands.size() + " 次");
        int chars = ThesisFullTextGenerator.countWords(written.get(0).content());
        assertTrue(chars <= 4400, "至少要落回上限以内，实际 " + chars + " 字");
        assertTrue(written.get(0).content().endsWith("。"),
                "截断必须停在整句上，不能留半句");
    }

    @Test
    void theFinalPolishingTitleSaysNothingAboutWordCounts() {
        // 用户无从判断「压缩至 3200 字」是好是坏，也不该让他们以为内容被丢掉。
        assertEquals("最终整合中", ThesisFullTextGenerator.FINAL_POLISHING_TITLE);
        assertTrue(!ThesisFullTextGenerator.FINAL_POLISHING_TITLE.matches(".*\\d.*"),
                "文案里不该出现数字");
    }
}
