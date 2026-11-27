package com.hub.backend.modules.agent;

import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolCallDelta;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.agent.dto.AgentToolCallRecord;
import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.entity.AgentModelEntity;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.agent.repository.AgentModelRepository;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.modules.agent.tool.AgentTool;
import com.hub.backend.modules.agent.tool.AgentToolRegistry;
import com.hub.backend.modules.agent.tool.ToolContext;
import com.hub.backend.modules.appconfig.AppConfigService;
import com.hub.backend.modules.appconfig.ModuleChatModelService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.ChatEventPublisher;
import com.hub.backend.modules.chat.ChatRequestRegistry;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatCompletionResponse;
import com.hub.backend.modules.chat.dto.ChatMessagePayload;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.file.DocumentTextExtractor;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentChatServiceTest {

    private static final long USER_ID = 7L;
    private static final long SITE_ID = 1L;
    private static final long AGENT_ID = 11L;
    private static final long MODEL_ID = 20L;
    private static final long CONVERSATION_ID = 42L;
    private static final long MESSAGE_ID = 101L;
    private static final String AGENT_SN = "agent-sn";
    private static final String MODEL_KEY = "deepseek-v4.1-flash";
    private static final String UPSTREAM_MODEL = "deepseek-v4.1-flash-0910";
    private static final String SYSTEM_PROMPT = "You are the Python specialist.";
    private static final CurrentUser CURRENT_USER = new CurrentUser(USER_ID, SITE_ID, "demo");

    @Mock
    private LlmClient llmClient;
    @Mock
    private AppConfigService appConfigService;
    @Mock
    private AgentService agentService;
    @Mock
    private AgentModelRepository agentModels;
    @Mock
    private AgentChatMessageRepository chatMessages;
    @Mock
    private LlmModelRepository models;
    @Mock
    private ModelUseResolver modelResolver;
    @Mock
    private ModuleChatModelService moduleChatModels;
    @Mock
    private UserRelayCredentialResolver relayCredentials;
    @Mock
    private FileService files;

    private AgentChatService agentChatService;

    /** 新一轮落库后用这个 id 当 reply_to，便于断言具体值 */
    private static final long STORED_USER_MESSAGE_ID = 11L;

    @BeforeEach
    void setUp() {
        when(appConfigService.defaultModelForSite(SITE_ID)).thenReturn(MODEL_KEY);
        // 新一轮落库后要拿 user 消息 id 当 reply_to（切版本时配对答案），给个带 id 的桩
        AgentChatMessageEntity storedUserMessage = new AgentChatMessageEntity(
                CONVERSATION_ID, null, 99, "user", "hi", null, null, null,
                AgentChatMessageEntity.STATUS_COMPLETED, null, null, null, null);
        assignId(storedUserMessage, STORED_USER_MESSAGE_ID);
        when(agentService.appendUserMessage(any(), any(), any())).thenReturn(storedUserMessage);

        agentChatService = serviceWith(new AgentToolRegistry(List.of()));
    }

    private AgentChatService serviceWith(AgentToolRegistry registry) {
        return new AgentChatService(
                llmClient,
                appConfigService,
                agentService,
                agentModels,
                models,
                chatMessages,
                modelResolver,
                new ChatRequestRegistry(Clock.systemUTC(), new ChatProperties()),
                relayCredentials,
                registry,
                new ChatAttachmentLoader(files, new DocumentTextExtractor(),
                        mock(com.hub.backend.infra.llm.AudioTranscriptionClient.class)),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                moduleChatModels);
    }

    @Test
    void usesSuperAgentModuleDefaultWithoutApplyingItToOrdinaryAgents() {
        AgentEntity platformAgent = agent();
        platformAgent.setSn(AgentEntity.SN_SUPER_AGENT);
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(platformAgent);
        when(moduleChatModels.resolveAllForSite(SITE_ID, ModuleChatModelService.SUPER_AGENT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));

        agentChatService.prepare(AGENT_SN, request(null), CURRENT_USER);

        verify(moduleChatModels).resolveAllForSite(SITE_ID, ModuleChatModelService.SUPER_AGENT);
        verify(agentModels, never()).findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID);
    }

    @Test
    void preservesTheOriginalConstructorForExistingCallers() {
        AgentChatService compatible = new AgentChatService(
                llmClient, appConfigService, agentService, agentModels, models, chatMessages,
                modelResolver, new ChatRequestRegistry(Clock.systemUTC(), new ChatProperties()),
                relayCredentials, new AgentToolRegistry(List.of()),
                new ChatAttachmentLoader(files, new DocumentTextExtractor(),
                        mock(com.hub.backend.infra.llm.AudioTranscriptionClient.class)),
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertNotNull(compatible);
    }

    @Test
    void usesEducationModuleOnlyWhenWorkspaceIsMarked() {
        AgentEntity agent = agent();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(moduleChatModels.resolveAllForSite(SITE_ID, ModuleChatModelService.AI_EDUCATION))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        ChatCompletionRequest educationRequest = new ChatCompletionRequest(null,
                List.of(new ChatMessagePayload(LlmChatMessage.ROLE_USER, "hi")),
                0.2, 256, null, null, null, null, null, null, "ai-education");

        agentChatService.prepare(AGENT_SN, educationRequest, CURRENT_USER);

        verify(moduleChatModels).resolveAllForSite(SITE_ID, ModuleChatModelService.AI_EDUCATION);
    }

    @Test
    void ignoresUnknownWorkspaceInsteadOfSelectingEducationModuleModels() {
        AgentEntity agent = agent();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        ChatCompletionRequest unknownWorkspaceRequest = new ChatCompletionRequest(null,
                List.of(new ChatMessagePayload(LlmChatMessage.ROLE_USER, "hi")),
                0.2, 256, null, null, null, null, null, null, "arbitrary-module");

        agentChatService.prepare(AGENT_SN, unknownWorkspaceRequest, CURRENT_USER);

        verify(moduleChatModels, never()).resolveAllForSite(anyLong(), any());
        verify(modelResolver).resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT);
    }

    @Test
    void usesTheEffectiveSiteDefaultWhenTheAgentHasNoConfiguredModels() {
        String siteDefault = "deepseek-v4-1-flash-260910";
        AgentEntity agent = agent();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(appConfigService.defaultModelForSite(SITE_ID)).thenReturn(siteDefault);
        when(modelResolver.resolveAllForSite(
                SITE_ID, siteDefault, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, siteDefault, siteDefault, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));

        agentChatService.prepare(AGENT_SN, request(null), CURRENT_USER);

        verify(modelResolver).resolveAllForSite(
                SITE_ID, siteDefault, LlmModelEntity.TYPE_CHAT);
    }

    @Test
    void fallsBackToAnotherConfiguredModelWhenTheDefaultOneWasRetired() {
        String remaining = "deepseek-v4-1-flash-260910";
        AgentEntity agent = agent();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of(
                new AgentModelEntity(AGENT_ID, MODEL_ID, true, 0),
                new AgentModelEntity(AGENT_ID, MODEL_ID + 1, false, 1)));
        when(models.findById(MODEL_ID)).thenReturn(Optional.of(chatModel(MODEL_KEY, "DISABLED")));
        when(models.findById(MODEL_ID + 1)).thenReturn(Optional.of(chatModel(remaining, "ACTIVE")));
        when(modelResolver.resolveAllForSite(SITE_ID, remaining, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID + 1, remaining, remaining, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));

        // 运营退役默认模型不该让整个智能体 404
        agentChatService.prepare(AGENT_SN, request(null), CURRENT_USER);

        verify(modelResolver).resolveAllForSite(
                SITE_ID, remaining, LlmModelEntity.TYPE_CHAT);
    }

    @Test
    void fallsBackToTheSiteDefaultWhenEveryConfiguredModelIsUnusable() {
        AgentEntity agent = agent();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of(
                new AgentModelEntity(AGENT_ID, MODEL_ID, true, 0)));
        // 配置行指向的模型已被物理删除
        when(models.findById(MODEL_ID)).thenReturn(Optional.empty());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));

        // 站点默认不在该智能体的配置清单里也必须放行，否则刚回退出来的模型又被 enabled 检查挡回
        agentChatService.prepare(AGENT_SN, request(null), CURRENT_USER);

        verify(modelResolver).resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT);
    }

    @Test
    void prependsTheAgentPromptAndPersistsOnlyToAgentTables() {
        AgentEntity agent = agent();
        AgentConversationEntity conversation = conversation();
        AgentChatMessageEntity reply = new AgentChatMessageEntity(
                CONVERSATION_ID, MODEL_ID, 2, AgentChatMessageEntity.ROLE_ASSISTANT, "pong",
                null, null, null, AgentChatMessageEntity.STATUS_COMPLETED, new LlmUsage(10, 2, 12),
                "cmpl-1", null, null);
        assignId(reply, MESSAGE_ID);

        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        when(agentService.openConversation(null, USER_ID, AGENT_ID, MODEL_ID, null))
                .thenReturn(conversation);
        when(agentService.appendAssistantMessage(
                CONVERSATION_ID, MODEL_ID, "pong", null, new LlmUsage(10, 2, 12), "cmpl-1", List.of(), null, STORED_USER_MESSAGE_ID))
                .thenReturn(reply);
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", UPSTREAM_MODEL, "pong", "stop", new LlmUsage(10, 2, 12)));

        ChatCompletionResponse response = agentChatService.complete(
                AGENT_SN, request(null), CURRENT_USER);

        assertEquals(CONVERSATION_ID, response.conversationId().longValue());
        assertEquals(MESSAGE_ID, response.messageId().longValue());
        assertEquals(MODEL_KEY, response.model());
        assertEquals("pong", response.content());

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(UPSTREAM_MODEL, command.getValue().model());
        assertEquals(2, command.getValue().messages().size());
        assertEquals(LlmChatMessage.ROLE_SYSTEM, command.getValue().messages().get(0).role());
        assertEquals(SYSTEM_PROMPT, command.getValue().messages().get(0).content());
        assertEquals("hi", command.getValue().messages().get(1).content());

        verify(agentService).appendUserMessage(CONVERSATION_ID, "hi", List.of());
        verify(agentService).registerUse(AGENT_ID);
        verify(agentService).appendAssistantMessage(
                CONVERSATION_ID, MODEL_ID, "pong", null, new LlmUsage(10, 2, 12), "cmpl-1", List.of(), null, STORED_USER_MESSAGE_ID);
    }

    @Test
    void streamsThinkingAndPersistsTheCompletedAnswerToTheAgentConversation() {
        AgentEntity agent = agent();
        AgentConversationEntity conversation = conversation();

        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        when(agentService.openConversation(null, USER_ID, AGENT_ID, MODEL_ID, null))
                .thenReturn(conversation);
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("stream-1", null, "think", null, false));
            consumer.accept(new LlmStreamChunk("stream-1", "Hel", null, null, false));
            consumer.accept(new LlmStreamChunk(
                    "stream-1", "lo", null, "stop", false, new LlmUsage(10, 2, 12)));
            consumer.accept(LlmStreamChunk.done("stream-1"));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        agentChatService.stream(AGENT_SN, request(null), CURRENT_USER, events::add);

        assertEquals(
                List.of(ChatStreamEvent.EVENT_CREATED, ChatStreamEvent.EVENT_THINKING,
                        ChatStreamEvent.EVENT_DELTA, ChatStreamEvent.EVENT_DELTA,
                        ChatStreamEvent.EVENT_COMPLETED),
                events.stream().map(ChatStreamEvent::event).toList());
        verify(agentService).appendAssistantMessage(
                CONVERSATION_ID, MODEL_ID, "Hello", "think", new LlmUsage(10, 2, 12), null,
                List.of(), null, STORED_USER_MESSAGE_ID);
        verify(agentService, never()).appendAssistantFailure(any(), any(), any(), any(), any());
        assertNull(events.get(0).data().get("messageId"));
    }

    @Test
    void keepsAggregatedUsageWhenTheStreamIsStopped() {
        stubStreamingConversation();
        List<ChatStreamEvent> events = new ArrayList<>();

        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk(
                    "stream-1", "Hel", null, null, false, new LlmUsage(10, 2, 12)));

            String requestId = (String) events.get(0).data().get("requestId");
            agentChatService.cancelGeneration(requestId, USER_ID);

            consumer.accept(new LlmStreamChunk("stream-1", "lo", null, null, false));
            return null;
        }).when(llmClient).stream(any(), any());

        agentChatService.stream(AGENT_SN, request(null), CURRENT_USER, events::add);

        assertEquals(
                List.of(ChatStreamEvent.EVENT_CREATED, ChatStreamEvent.EVENT_DELTA,
                        ChatStreamEvent.EVENT_CANCELLED),
                events.stream().map(ChatStreamEvent::event).toList());
        verify(agentService).appendAssistantCancelled(
                CONVERSATION_ID, MODEL_ID, "Hel", null, new LlmUsage(10, 2, 12), List.of(), null, STORED_USER_MESSAGE_ID);
        verify(agentService, never()).appendAssistantMessage(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void keepsAggregatedUsageWhenTheClientDisconnects() {
        stubStreamingConversation();
        doAnswer(invocation -> {
            invocation.<Consumer<LlmStreamChunk>>getArgument(1).accept(new LlmStreamChunk(
                    "stream-1", "Hel", null, null, false, new LlmUsage(10, 2, 12)));
            return null;
        }).when(llmClient).stream(any(), any());

        AtomicBoolean firstFrame = new AtomicBoolean(true);
        ChatEventPublisher publisher = event -> {
            if (firstFrame.getAndSet(false)) {
                return;
            }
            throw new UncheckedIOException(new IOException("broken pipe"));
        };

        agentChatService.stream(AGENT_SN, request(null), CURRENT_USER, publisher);

        verify(agentService).appendAssistantCancelled(
                CONVERSATION_ID, MODEL_ID, "", null, new LlmUsage(10, 2, 12), List.of(), null, STORED_USER_MESSAGE_ID);
        verify(agentService, never()).appendAssistantMessage(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /* ------------------------- 服务端重建上下文 ------------------------- */

    @Test
    void rebuildsTheContextFromStoredMessagesAndSendsOnlyTheNewTurn() {
        AgentEntity agent = agent();
        AgentConversationEntity conversation = conversation();
        AgentChatMessageEntity reply = new AgentChatMessageEntity(
                CONVERSATION_ID, MODEL_ID, 4, AgentChatMessageEntity.ROLE_ASSISTANT, "ok",
                null, null, null, AgentChatMessageEntity.STATUS_COMPLETED, new LlmUsage(1, 1, 2),
                "cmpl-2", null, null);
        assignId(reply, MESSAGE_ID);

        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        when(agentService.openConversation(CONVERSATION_ID, USER_ID, AGENT_ID, MODEL_ID, null))
                .thenReturn(conversation);
        // 仓储按序号倒序返回：最新的在前
        when(chatMessages.findByAgentConversationIdAndActiveTrueAndStatusOrderBySequenceNoDesc(
                CONVERSATION_ID, AgentChatMessageEntity.STATUS_COMPLETED, PageRequest.of(0, 40)))
                .thenReturn(List.of(
                        stored(2, AgentChatMessageEntity.ROLE_ASSISTANT, "pong"),
                        stored(1, AgentChatMessageEntity.ROLE_USER, "hi")));
        when(agentService.appendAssistantMessage(
                CONVERSATION_ID, MODEL_ID, "ok", null, new LlmUsage(1, 1, 2), "cmpl-2", List.of(), null, STORED_USER_MESSAGE_ID))
                .thenReturn(reply);
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-2", UPSTREAM_MODEL, "ok", "stop", new LlmUsage(1, 1, 2)));

        agentChatService.complete(AGENT_SN,
                new ChatCompletionRequest(null,
                        List.of(new ChatMessagePayload(LlmChatMessage.ROLE_USER, "and then?")),
                        0.2, 256, CONVERSATION_ID, null),
                CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(
                List.of(SYSTEM_PROMPT, "hi", "pong", "and then?"),
                command.getValue().messages().stream().map(LlmChatMessage::content).toList());
    }

    @Test
    void legacyHistoryDropsTheTurnBeingSentAndSkipsUnusableEntries() {
        List<LlmChatMessage> history = AgentChatService.legacyHistory(List.of(
                new ChatMessagePayload(LlmChatMessage.ROLE_USER, "第一问"),
                new ChatMessagePayload(LlmChatMessage.ROLE_ASSISTANT, "  "),
                new ChatMessagePayload(LlmChatMessage.ROLE_ASSISTANT, "第一答"),
                new ChatMessagePayload(LlmChatMessage.ROLE_USER, "第二问")));

        assertEquals(List.of("第一问", "第一答"),
                history.stream().map(LlmChatMessage::content).toList());
    }

    @Test
    void budgetKeepsTheNewestMessagesAndAlwaysKeepsOne() {
        String big = "x".repeat(10_000);

        List<LlmChatMessage> trimmed = AgentChatService.trimToBudget(List.of(
                LlmChatMessage.user(big), LlmChatMessage.assistant(big), LlmChatMessage.user(big)));
        assertEquals(2, trimmed.size());

        String oversized = "x".repeat(30_000);
        assertEquals(1, AgentChatService.trimToBudget(List.of(LlmChatMessage.user(oversized))).size());
        assertEquals(List.of(), AgentChatService.trimToBudget(List.of()));
    }

    /* ------------------------- 工具上下文 ------------------------- */

    @Test
    void toolsReceiveTheImagesTheUserAttachedToThisTurn() {
        stubStreamingConversation();
        when(files.download(9L, CURRENT_USER)).thenReturn(storedImage(9L, "image/png", "shirt.png"));

        // 电商类工具（换背景、试穿）靠这段上下文拿参考图，模型自己拼不出附件字节
        java.util.concurrent.atomic.AtomicReference<ToolContext> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentChatService service = serviceWith(new AgentToolRegistry(List.of(new AgentTool() {
            @Override
            public LlmToolDefinition definition() {
                return new LlmToolDefinition(
                        "stub_tool", "test tool", Map.of("type", "object"));
            }

            @Override
            public AgentTool.ToolOutcome execute(LlmToolCall call, ToolContext context) {
                seen.set(context);
                return AgentTool.ToolOutcome.ok("{}");
            }
        })));

        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s-1", null, null, null, false, null,
                    List.of(new LlmToolCallDelta(0, "call-1", "stub_tool", "{}"))));
            return null;
        }).doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk(
                    "s-2", "好了", null, "stop", false, new LlmUsage(3, 1, 4)));
            consumer.accept(LlmStreamChunk.done("s-2"));
            return null;
        }).when(llmClient).stream(any(), any());

        service.stream(AGENT_SN,
                new ChatCompletionRequest(null,
                        List.of(new ChatMessagePayload(
                                LlmChatMessage.ROLE_USER, "把第一张图的背景换成纯白", List.of(9L))),
                        0.2, 256, null, null),
                CURRENT_USER,
                event -> {
                });

        assertNotNull(seen.get(), "工具没有被调用");
        assertEquals(USER_ID, seen.get().currentUser().userId());
        assertEquals(1, seen.get().images().size());
        assertEquals("image/png", seen.get().images().get(0).mimeType());
    }

    @Test
    void rejectsToolCallsThatWereNotOfferedToTheModel() {
        stubStreamingConversation();
        when(agentService.abilityKeysOf(AGENT_ID)).thenReturn(List.of("allowed_tool"));

        AtomicBoolean forbiddenExecuted = new AtomicBoolean(false);
        AgentTool allowed = fakeTool("allowed_tool", AgentTool.ToolOutcome.ok("allowed"));
        AgentTool forbidden = new AgentTool() {
            @Override
            public LlmToolDefinition definition() {
                return new LlmToolDefinition(
                        "forbidden_tool", "must not run", Map.of("type", "object"));
            }

            @Override
            public ToolOutcome execute(LlmToolCall call, ToolContext context) {
                forbiddenExecuted.set(true);
                return ToolOutcome.ok("should never happen");
            }
        };
        AgentChatService service = serviceWith(new AgentToolRegistry(List.of(allowed, forbidden)));

        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s-1", null, null, null, false, null,
                    List.of(new LlmToolCallDelta(0, "call-1", "forbidden_tool", "{}"))));
            return null;
        }).doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk(
                    "s-2", "拒绝了未授权调用", null, "stop", false, new LlmUsage(3, 1, 4)));
            consumer.accept(LlmStreamChunk.done("s-2"));
            return null;
        }).when(llmClient).stream(any(), any());

        service.stream(AGENT_SN, request(null), CURRENT_USER, event -> {
        });

        assertFalse(forbiddenExecuted.get(), "未声明的工具不应被执行");

        ArgumentCaptor<LlmChatCommand> commands = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient, times(2)).stream(commands.capture(), any());
        assertEquals(List.of("allowed_tool"), commands.getAllValues().get(0).tools().stream()
                .map(LlmToolDefinition::name)
                .toList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolCallRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(agentService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("拒绝了未授权调用"), isNull(), any(), isNull(),
                records.capture(), any(), any());
        assertEquals(1, records.getValue().size());
        assertFalse(records.getValue().get(0).success());
        assertEquals("未授权的工具调用：forbidden_tool", records.getValue().get(0).content());
    }

    /* ------------------------- 工具调用落库 ------------------------- */

    @Test
    @SuppressWarnings("unchecked")
    void persistsTheToolCallsTogetherWithTheAnswer() {
        AgentEntity agent = agent();
        AgentConversationEntity conversation = conversation();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        when(agentService.openConversation(null, USER_ID, AGENT_ID, MODEL_ID, null))
                .thenReturn(conversation);

        AgentChatService service = serviceWith(new AgentToolRegistry(
                List.of(fakeTool("stub_tool", AgentTool.ToolOutcome.ok(
                        "{\"ok\":true}", Map.of("images", List.of(Map.of("url", "/file/9"))))))));

        // 第一轮：模型请求调用工具；第二轮：拿到结果后正常作答
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s-1", null, null, null, false, null,
                    List.of(new LlmToolCallDelta(0, "call-1", "stub_tool", "{}"))));
            return null;
        }).doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk(
                    "s-2", "好了", null, "stop", false, new LlmUsage(3, 1, 4)));
            consumer.accept(LlmStreamChunk.done("s-2"));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        service.stream(AGENT_SN, request(null), CURRENT_USER, events::add);

        assertEquals(
                List.of(ChatStreamEvent.EVENT_CREATED, ChatStreamEvent.EVENT_TOOL_CALL,
                        ChatStreamEvent.EVENT_TOOL_RESULT, ChatStreamEvent.EVENT_DELTA,
                        ChatStreamEvent.EVENT_COMPLETED),
                events.stream().map(ChatStreamEvent::event).toList());

        ArgumentCaptor<List<AgentToolCallRecord>> captured = ArgumentCaptor.forClass(List.class);
        verify(agentService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("好了"), isNull(), any(), isNull(),
                captured.capture(), any(), any());

        List<AgentToolCallRecord> saved = captured.getValue();
        assertEquals(1, saved.size());
        assertEquals("call-1", saved.get(0).id());
        assertEquals("stub_tool", saved.get(0).name());
        assertTrue(saved.get(0).success());
        assertEquals("{\"ok\":true}", saved.get(0).content());
        assertEquals(List.of(Map.of("url", "/file/9")), saved.get(0).meta().get("images"));
    }

    @Test
    void keepsExecutedToolCallsWhenTheUserStopsGeneration() {
        stubStreamingConversation();
        AgentChatService service = serviceWith(new AgentToolRegistry(
                List.of(fakeTool("stub_tool", AgentTool.ToolOutcome.ok("done")))));

        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s-1", null, null, null, false, null,
                    List.of(new LlmToolCallDelta(0, "call-1", "stub_tool", "{}"))));
            return null;
        }).doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            // 这帧触发 publisher，publisher 里点停止；下一帧才检测到取消
            consumer.accept(new LlmStreamChunk("s-2", "Hel", null, null, false));
            consumer.accept(new LlmStreamChunk("s-2", "lo", null, null, false));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        ChatEventPublisher publisher = event -> {
            events.add(event);
            if (ChatStreamEvent.EVENT_DELTA.equals(event.event())) {
                service.cancelGeneration(
                        (String) events.get(0).data().get("requestId"), USER_ID);
            }
        };

        service.stream(AGENT_SN, request(null), CURRENT_USER, publisher);

        ArgumentCaptor<List<AgentToolCallRecord>> captured = ArgumentCaptor.forClass(List.class);
        verify(agentService).appendAssistantCancelled(
                eq(CONVERSATION_ID), eq(MODEL_ID), any(), any(), any(), captured.capture(), any(), any());
        assertEquals(1, captured.getValue().size());
        assertEquals("stub_tool", captured.getValue().get(0).name());
    }

    private static AgentTool fakeTool(String name, AgentTool.ToolOutcome outcome) {
        return new AgentTool() {
            @Override
            public LlmToolDefinition definition() {
                return new LlmToolDefinition(name, "test tool", Map.of("type", "object"));
            }

            @Override
            public AgentTool.ToolOutcome execute(LlmToolCall call, ToolContext context) {
                return outcome;
            }
        };
    }

    /* ------------------------- 多模态 ------------------------- */

    @Test
    void sendsTheImageTogetherWithTheNewTurn() {
        stubStreamingConversation();
        stubCompletion("这件衣服换成白色背景");
        when(files.download(9L, CURRENT_USER)).thenReturn(storedImage(9L, "image/png", "shirt.png"));

        agentChatService.complete(AGENT_SN,
                new ChatCompletionRequest(null,
                        List.of(new ChatMessagePayload(
                                LlmChatMessage.ROLE_USER, "这件衣服换成白色背景", List.of(9L))),
                        0.2, 256, null, null),
                CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        LlmChatMessage user = command.getValue().messages()
                .get(command.getValue().messages().size() - 1);
        assertEquals(LlmChatMessage.ROLE_USER, user.role());
        assertEquals(1, user.images().size());
        assertEquals("image/png", user.images().get(0).mimeType());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatAttachmentRef>> refs = ArgumentCaptor.forClass(List.class);
        verify(agentService).appendUserMessage(
                eq(CONVERSATION_ID), eq("这件衣服换成白色背景"), refs.capture());
        assertEquals(1, refs.getValue().size());
        assertEquals(9L, refs.getValue().get(0).id().longValue());
    }

    @Test
    void onlyTheNewestImagesAreReplayedAndOlderOnesAreMarked() {
        stubStreamingConversation();
        stubCompletion("第五问");
        // 4 条历史各带 2 张图 = 8 张，超过 6 张的预算
        when(chatMessages.findByAgentConversationIdAndActiveTrueAndStatusOrderBySequenceNoDesc(
                CONVERSATION_ID, AgentChatMessageEntity.STATUS_COMPLETED, PageRequest.of(0, 40)))
                .thenReturn(List.of(
                        stored(4, AgentChatMessageEntity.ROLE_USER, "第四问", "[]"),
                        stored(3, AgentChatMessageEntity.ROLE_USER, "第三问", "[]"),
                        stored(2, AgentChatMessageEntity.ROLE_USER, "第二问", "[]"),
                        stored(1, AgentChatMessageEntity.ROLE_USER, "第一问", "[]")));
        when(agentService.attachmentsOf(any())).thenReturn(List.of(
                new ChatAttachmentRef(1L, "image/png", "a.png", 3, "/api/v1/files/1/content"),
                new ChatAttachmentRef(2L, "image/png", "b.png", 3, "/api/v1/files/2/content")));
        when(files.download(anyLong(), any()))
                .thenAnswer(call -> storedImage(call.getArgument(0), "image/png", "a.png"));

        agentChatService.complete(AGENT_SN,
                new ChatCompletionRequest(null,
                        List.of(new ChatMessagePayload(LlmChatMessage.ROLE_USER, "第五问")),
                        0.2, 256, null, null),
                CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        List<LlmChatMessage> history = command.getValue().messages().subList(1, 5);

        // 最旧的一条被省略，其余三条各带两张
        assertEquals("第一问\n[图片已省略]", history.get(0).content());
        assertEquals(0, history.get(0).images().size());
        for (int index = 1; index < history.size(); index++) {
            assertEquals(2, history.get(index).images().size());
        }
    }

    private static AgentChatMessageEntity stored(int sequence, String role, String content) {
        return stored(sequence, role, content, null);
    }

    private static AgentChatMessageEntity stored(int sequence, String role, String content,
                                                 String attachmentsJson) {
        return new AgentChatMessageEntity(CONVERSATION_ID, null, sequence, role, content,
                null, null, attachmentsJson,
                AgentChatMessageEntity.STATUS_COMPLETED, null, null, null, null);
    }

    /** 一个归属当前用户、可以正常读出内容的图片附件 */
    private DownloadedFile storedImage(long fileId, String mimeType, String name) {
        UploadedFileEntity row = new UploadedFileEntity(
                USER_ID, CONVERSATION_ID, "2026/10/22/x.png", name,
                mimeType, "png", FileAssetType.CHAT_ATTACHMENT, 3, "checksum");
        try {
            var field = UploadedFileEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(row, fileId);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return new DownloadedFile(row, new ByteArrayResource(new byte[]{1, 2, 3}));
    }

    private static ChatCompletionRequest request(Long conversationId) {
        return new ChatCompletionRequest(
                null,
                List.of(new ChatMessagePayload(LlmChatMessage.ROLE_USER, "hi")),
                0.2,
                256,
                conversationId,
                null);
    }

    private static AgentEntity agent() {
        AgentEntity agent = new AgentEntity(SITE_ID, null, "agent-key", AGENT_SN, "Python Expert");
        agent.setSystemPrompt(SYSTEM_PROMPT);
        agent.setScope(AgentEntity.SCOPE_PUBLIC);
        agent.setVisibility(AgentEntity.VISIBILITY_PUBLIC);
        agent.setPublishStatus(AgentEntity.PUBLISH_PUBLISHED);
        assignId(agent, AGENT_ID);
        return agent;
    }

    private static AgentConversationEntity conversation() {
        AgentConversationEntity conversation = new AgentConversationEntity(
                SITE_ID, USER_ID, AGENT_ID, MODEL_ID, null);
        assignId(conversation, CONVERSATION_ID);
        return conversation;
    }

    private void stubStreamingConversation() {
        AgentEntity agent = agent();
        AgentConversationEntity conversation = conversation();
        when(agentService.requireVisibleBySn(AGENT_SN, CURRENT_USER)).thenReturn(agent);
        when(agentModels.findByAgentIdOrderBySortOrderAscIdAsc(AGENT_ID)).thenReturn(List.of());
        when(modelResolver.resolveAllForSite(SITE_ID, MODEL_KEY, LlmModelEntity.TYPE_CHAT))
                .thenReturn(List.of(new ModelUseResolver.Resolved(
                        MODEL_ID, MODEL_KEY, UPSTREAM_MODEL, LlmModelEntity.TYPE_CHAT, true,
                        false, null, false, null, null)));
        when(agentService.openConversation(null, USER_ID, AGENT_ID, MODEL_ID, null))
                .thenReturn(conversation);
    }

    /** 阻塞补全路径的最小 stub：模型直接返回一段正文 */
    private void stubCompletion(String answer) {
        AgentChatMessageEntity reply = new AgentChatMessageEntity(
                CONVERSATION_ID, MODEL_ID, 2, AgentChatMessageEntity.ROLE_ASSISTANT, answer,
                null, null, null, AgentChatMessageEntity.STATUS_COMPLETED,
                new LlmUsage(1, 1, 2), "cmpl-x", null, null);
        assignId(reply, MESSAGE_ID);
        when(agentService.appendAssistantMessage(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(reply);
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-x", UPSTREAM_MODEL, answer, "stop", new LlmUsage(1, 1, 2)));
    }

    private static LlmModelEntity chatModel(String modelKey, String status) {
        LlmModelEntity model = new LlmModelEntity(modelKey, modelKey, "DeepSeek", modelKey);
        model.setModelType(LlmModelEntity.TYPE_CHAT);
        model.setStatus(status);
        return model;
    }

    private static void assignId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
