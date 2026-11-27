package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserGatewayTokenService;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 论文模块必须按「站点 key → 上游模型名」的映射发请求。
 *
 * <p>线上故障的形态：页面选的是 {@code deepseek-v4.1-flash}，网关只认
 * {@code deepseek-v4-1-flash-260910}；对话、智能体、画布都走路由层，只有论文模块把站点
 * key 原样发出去，于是 {@code 503 model_not_found}。</p>
 *
 * <p><b>这两条测试断言的是「真正发出去的 model 字段」，不是解析方法本身。</b>
 * 上一版的教训：只测 {@code resolveModelKey()} 会假绿 —— 那个方法当时是死代码，
 * {@code generate()} 内部根本没调用它，测试全绿而线上照样发站点 key。</p>
 */
class ThesisModelRouteTest {

    private static final CurrentUser USER = new CurrentUser(42L, 1L, "demo");
    private static final Long SITE_ID = 7L;

    /** 站点 key：页面下拉里显示、请求里提交的那个。 */
    private static final String SITE_KEY = "deepseek-v4.1-flash";
    /** 上游真实模型名：网关渠道挂的那个。 */
    private static final String UPSTREAM_KEY = "deepseek-v4-1-flash-260910";

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

    /** Catalogue row whose route differs from its key — exactly the production shape. */
    private ModelUseResolver resolverWithRoute() {
        LlmModelEntity entity = new LlmModelEntity(SITE_KEY, "DeepSeek V4.1 Flash", "DeepSeek", UPSTREAM_KEY);
        entity.setModelType(LlmModelEntity.TYPE_CHAT);
        entity.setStatus(LlmModelEntity.STATUS_ACTIVE);

        LlmModelRepository repo = mock(LlmModelRepository.class);
        when(repo.findVisibleByModelKeyForResolution(any(), eq(SITE_KEY))).thenReturn(List.of(entity));

        return new ModelUseResolver(repo, new ObjectMapper(),
                mock(AppSiteSettingRepository.class), llmProperties);
    }

    private static ThesisFullTextGenerator.Brief brief() {
        return new ThesisFullTextGenerator.Brief(
                "基于大语言模型的金融舆情分析系统", "本科在读", "论文助手", "工学",
                "计算机科学与技术", 2, 2000, false, false, false, "");
    }

    private static ThesisOutlineNode chapter(String title) {
        return new ThesisOutlineNode("n-" + title, title, 1, List.of());
    }

    /** 记录每次请求的 model 字段，正常返回一段正文。 */
    private static final class RecordingClient implements LlmClient {
        final List<LlmChatCommand> commands = new ArrayList<>();

        @Override
        public LlmChatCompletion complete(LlmChatCommand command) {
            throw new UnsupportedOperationException("论文模块只走流式");
        }

        @Override
        public void stream(LlmChatCommand command, Consumer<LlmStreamChunk> onChunk) {
            commands.add(command);
            onChunk.accept(new LlmStreamChunk(null, "正文".repeat(500), null, null, false,
                    LlmUsage.empty(), List.of()));
        }
    }

    @Test
    void fullTextSendsTheUpstreamRouteNotTheSiteKey() {
        RecordingClient client = new RecordingClient();
        ThesisFullTextGenerator generator = new ThesisFullTextGenerator(
                client, llmProperties, newApiProperties, relayCredentials, resolverWithRoute());

        ThesisFullTextGenerator.Result result = generator.generate(
                brief(), SITE_KEY, SITE_ID,
                List.of(chapter("第一章 绪论"), chapter("第二章 文献综述")),
                List.of(), USER, progress -> { });

        assertEquals(2, client.commands.size());
        for (LlmChatCommand command : client.commands) {
            assertEquals(UPSTREAM_KEY, command.model(),
                    "论文全文必须发上游模型名，发站点 key 会被网关拒绝为 model_not_found");
        }
        assertEquals(SITE_KEY, result.modelKey(), "回显给客户端的仍是站点 key，不是上游名");
        assertEquals(2, result.chapters().size());
    }

    @Test
    void blankRequestFallsBackToTheDefaultAndStillResolvesItsRoute() {
        llmProperties.setDefaultModel(SITE_KEY);
        RecordingClient client = new RecordingClient();
        ThesisFullTextGenerator generator = new ThesisFullTextGenerator(
                client, llmProperties, newApiProperties, relayCredentials, resolverWithRoute());

        generator.generate(brief(), null, SITE_ID, List.of(chapter("第一章 绪论")),
                List.of(), USER, progress -> { });

        assertEquals(UPSTREAM_KEY, client.commands.get(0).model());
    }

    @Test
    void outlineGeneratorSendsTheUpstreamRouteNotTheSiteKey() {
        RecordingClient client = new RecordingClient();
        ThesisModelOutlineGenerator generator = new ThesisModelOutlineGenerator(
                client, llmProperties, newApiProperties, resolverWithRoute(),
                relayCredentials, new ObjectMapper());

        generator.generate(SITE_KEY, "本科在读", "论文助手", "工学", "计算机科学与技术",
                "基于大语言模型的金融舆情分析系统", 2, false, SITE_ID, USER);

        assertEquals(1, client.commands.size(), "应当只发起一次模型调用");
        assertEquals(UPSTREAM_KEY, client.commands.get(0).model(),
                "大纲生成也必须发上游模型名；发站点 key 就是 model_not_found");
    }

    @Test
    void outlineGeneratorResolvesItsFallbackToo() {
        llmProperties.setDefaultModel(SITE_KEY);
        RecordingClient client = new RecordingClient();
        ThesisModelOutlineGenerator generator = new ThesisModelOutlineGenerator(
                client, llmProperties, newApiProperties, resolverWithRoute(),
                relayCredentials, new ObjectMapper());

        generator.generate(null, "本科在读", "论文助手", "工学", "计算机",
                "题目", 2, false, SITE_ID, USER);

        assertEquals(UPSTREAM_KEY, client.commands.get(0).model());
    }
}
