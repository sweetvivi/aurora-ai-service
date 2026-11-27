package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 摘要阶段的调用行为。
 *
 * <p>这里锁住的核心承诺是「<b>摘要失败不能毁掉正文</b>」：正文是用户花了十几分钟和
 * 上万个 token 换来的，摘要只是一千多 token 的收尾步骤。如果摘要调不通就把整次生成判失败，
 * 用户会看到「生成失败」而正文其实早就写完了 —— 这是最不能接受的失败方式。</p>
 *
 * <p>另外验证提示词确实把正文带进去了：只给题目会让模型写出一篇「理想中的论文」的摘要，
 * 与正文结论对不上，这是摘要最隐蔽的质量问题。</p>
 */
class ThesisAbstractGenerationTest {

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

    private static com.hub.backend.modules.model.ModelUseResolver modelResolver() {
        // 本仓库的 ModelUseResolver 是 4 参（多一个 AppSiteSettingRepository，用于按站点覆盖模型），
        // 与本地 3 参版本不同 —— 移植测试时这里必须跟着改，否则 testCompile 直接失败。
        return new com.hub.backend.modules.model.ModelUseResolver(
                mock(com.hub.backend.modules.model.repository.LlmModelRepository.class),
                new ObjectMapper(),
                mock(com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository.class),
                new LlmProperties());
    }

    private static ThesisFullTextGenerator.Brief brief() {
        return new ThesisFullTextGenerator.Brief(
                "基于大语言模型的金融舆情分析系统", "本科在读", "论文助手", "工学",
                "计算机科学与技术", 3, 8000, true, false, false, "");
    }

    /** 只服务「摘要」这一次调用的最小客户端：返回预设文本或抛异常。 */
    private static final class AbstractClient implements LlmClient {
        private final String reply;
        private final LlmInvocationException failure;
        final List<LlmChatCommand> commands = new ArrayList<>();

        AbstractClient(String reply) {
            this.reply = reply;
            this.failure = null;
        }

        AbstractClient(LlmInvocationException failure) {
            this.reply = null;
            this.failure = failure;
        }

        @Override
        public LlmChatCompletion complete(LlmChatCommand command) {
            commands.add(command);
            if (failure != null) {
                throw failure;
            }
            return new LlmChatCompletion("abstract", "test-model", reply, "stop", LlmUsage.empty());
        }

        @Override
        public void stream(LlmChatCommand command, Consumer<LlmStreamChunk> onChunk) {
            commands.add(command);
            if (failure != null) {
                throw failure;
            }
            onChunk.accept(new LlmStreamChunk(null, reply, null, null, false,
                    LlmUsage.empty(), List.of()));
        }
    }

    private ThesisFullTextGenerator generator(LlmClient client) {
        return new ThesisFullTextGenerator(
                client, llmProperties, newApiProperties, relayCredentials, modelResolver());
    }

    private static List<ThesisFullTextGenerator.Chapter> body() {
        return List.of(
                new ThesisFullTextGenerator.Chapter("第一章 绪论",
                        1, "金融舆情信息分散在新闻与社交平台，人工研判滞后。本文研究自动分析方法。"),
                new ThesisFullTextGenerator.Chapter("第二章 相关技术",
                        1, "介绍 BERT 与情感分类的基本原理。"),
                new ThesisFullTextGenerator.Chapter("第三章 系统设计",
                        1, "系统采用分层架构，分为采集层、分析层与展示层。"));
    }

    @Test
    void writesTheFourSectionsFromTheBody() {
        AbstractClient client = new AbstractClient("""
                【中文摘要】
                针对金融舆情研判滞后的问题，本文构建了基于大语言模型的分析系统。
                【中文关键词】
                大语言模型；金融舆情；情感分析
                【英文摘要】
                To address the lag in financial sentiment analysis, this paper builds a system.
                【英文关键词】
                large language model; financial sentiment; BERT
                """);

        ThesisFullTextGenerator.Abstract result = generator(client)
                .writeAbstract(brief(), "test-model", null, body(), USER);

        assertTrue(result.chinese().startsWith("针对金融舆情研判滞后"), result.chinese());
        assertEquals("大语言模型；金融舆情；情感分析", result.chineseKeywords());
        assertTrue(result.english().startsWith("To address"), result.english());
        assertEquals("large language model; financial sentiment; BERT", result.englishKeywords());
        assertEquals(1, client.commands.size(), "摘要是一次调用，不该按章重复");
    }

    @Test
    void promptCarriesTheBodySoTheAbstractMatchesTheContent() {
        AbstractClient client = new AbstractClient("""
                【中文摘要】摘要。
                【中文关键词】甲
                【英文摘要】Abstract.
                【英文关键词】a
                """);

        generator(client).writeAbstract(brief(), "test-model", null, body(), USER);

        String prompt = client.commands.get(0).messages().stream()
                .map(message -> message.content())
                .reduce("", (a, b) -> a + b);
        // 三章的标题与关键内容都要出现在提示词里，否则摘要只能瞎写
        assertTrue(prompt.contains("第一章 绪论"), prompt);
        assertTrue(prompt.contains("分层架构"), prompt);
        assertTrue(prompt.contains("BERT"), prompt);
        // 系统提示里必须禁止编造，这是摘要最常见的失真点
        assertTrue(prompt.contains("不得编造") || prompt.contains("不得出现"), prompt);
    }

    @Test
    void failureYieldsEmptyInsteadOfThrowing() {
        // 上游空响应（推理模型把预算烧在思考上）—— 正文已经写好了，这里必须只是空摘要
        AbstractClient client = new AbstractClient(
                new LlmInvocationException("model returned an empty response"));

        ThesisFullTextGenerator.Abstract result = generator(client)
                .writeAbstract(brief(), "test-model", null, body(), USER);

        assertTrue(result.isEmpty(), "摘要失败必须降级为空，不能把异常抛给调用方");
    }

    @Test
    void garbageReplyYieldsEmpty() {
        AbstractClient client = new AbstractClient("好的，我已经为你写好了摘要，请查收。");

        assertTrue(generator(client).writeAbstract(brief(), "test-model", null, body(), USER).isEmpty());
    }

    @Test
    void noChaptersMeansNoCall() {
        AbstractClient client = new AbstractClient("【中文摘要】不该被调用");

        ThesisFullTextGenerator.Abstract result = generator(client)
                .writeAbstract(brief(), "test-model", null, List.of(), USER);

        assertTrue(result.isEmpty());
        assertEquals(0, client.commands.size(), "没有正文就没有素材，不该白花一次调用");
    }

    @Test
    void appendixAndBackMatterAreExcludedFromTheMaterial() {
        AbstractClient client = new AbstractClient("""
                【中文摘要】摘要。
                【中文关键词】甲
                【英文摘要】Abstract.
                【英文关键词】a
                """);
        List<ThesisFullTextGenerator.Chapter> withAppendix = new ArrayList<>(body());
        withAppendix.add(new ThesisFullTextGenerator.Chapter(
                ThesisFullTextGenerator.APPENDIX_TITLE, 1, "create table t_user (id bigint);"));
        withAppendix.add(new ThesisFullTextGenerator.Chapter("参考文献", 1, "[1] 张三. 论文[J]."));
        withAppendix.add(new ThesisFullTextGenerator.Chapter("致谢", 1, "感谢导师。"));

        generator(client).writeAbstract(brief(), "test-model", null, withAppendix, USER);

        String prompt = client.commands.get(0).messages().stream()
                .map(message -> message.content())
                .reduce("", (a, b) -> a + b);
        // 建表语句、参考文献、致谢都不该进入摘要素材：那不构成论文的论证
        assertTrue(!prompt.contains("create table"), prompt);
        assertTrue(!prompt.contains("感谢导师"), prompt);
    }
}