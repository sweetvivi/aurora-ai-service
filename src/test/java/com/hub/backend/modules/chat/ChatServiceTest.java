package com.hub.backend.modules.chat;

import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmToolCallDelta;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatArtifactContext;
import com.hub.backend.modules.chat.dto.ChatCompletionResponse;
import com.hub.backend.modules.chat.dto.ChatMessagePayload;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.conversation.entity.ChatMessageEntity;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.agent.ChatAttachmentLoader;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.agent.tool.EditImageTool;
import com.hub.backend.modules.agent.tool.GenerateDocxTool;
import com.hub.backend.modules.agent.tool.GenerateMarkdownTool;
import com.hub.backend.modules.agent.tool.GenerateSpreadsheetTool;
import com.hub.backend.modules.agent.tool.ImageGenerationTool;
import com.hub.backend.modules.agent.tool.AgentTool;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final long CONVERSATION_ID = 42L;
    private static final long USER_ID = 7L;
    private static final long MODEL_ID = 20L;
    private static final long MESSAGE_ID = 101L;
    private static final String MODEL_KEY = "deepseek-v4.1-flash";
    private static final CurrentUser CURRENT_USER = new CurrentUser(USER_ID, 1L, "demo");

    private static final Long SITE_ID = 1L;

    @Mock
    private LlmClient llmClient;
    @Mock
    private LlmModelRepository modelRepository;
    @Mock
    private AppSiteSettingRepository settingRepository;
    @Mock
    private ConversationService conversationService;
    @Mock
    private UserRelayCredentialResolver relayCredentials;
    @Mock
    private GenerateMarkdownTool generateMarkdownTool;
    @Mock
    private GenerateSpreadsheetTool generateSpreadsheetTool;
    @Mock
    private GenerateDocxTool generateDocxTool;
    @Mock
    private ImageGenerationTool imageGenerationTool;
    @Mock
    private EditImageTool editImageTool;
    @Mock
    private ChatAttachmentLoader attachmentLoader;
    @Mock
    private ChatImageFocusResolver imageFocusResolver;

    private LlmProperties llmProperties;
    private ChatProperties chatProperties;
    private ChatRequestRegistry requestRegistry;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.setDefaultModel(MODEL_KEY);
        chatProperties = new ChatProperties();
        requestRegistry = new ChatRequestRegistry(Clock.systemUTC(), chatProperties);

        when(attachmentLoader.loadForNewMessage(any(), any()))
                .thenReturn(ChatAttachmentLoader.Loaded.empty());
        when(attachmentLoader.refsFor(any(), any())).thenReturn(List.of());
        when(generateMarkdownTool.definition()).thenReturn(new LlmToolDefinition(
                GenerateMarkdownTool.TOOL_NAME, "md", Map.of("type", "object")));
        when(generateSpreadsheetTool.definition()).thenReturn(new LlmToolDefinition(
                GenerateSpreadsheetTool.TOOL_NAME, "xlsx", Map.of("type", "object")));
        when(generateDocxTool.definition()).thenReturn(new LlmToolDefinition(
                GenerateDocxTool.TOOL_NAME, "docx", Map.of("type", "object")));
        when(imageGenerationTool.definition()).thenReturn(new LlmToolDefinition(
                ImageGenerationTool.TOOL_NAME, "image", Map.of("type", "object")));
        when(editImageTool.definition()).thenReturn(new LlmToolDefinition(
                EditImageTool.TOOL_NAME, "edit", Map.of("type", "object")));
        when(imageFocusResolver.resolve(any(), any(), any())).thenReturn(List.of());
        when(imageFocusResolver.conversationHasGeneratedImage(anyLong(), anyLong())).thenReturn(false);

        when(settingRepository.findBySiteIdOrderBySettingKeyAsc(SITE_ID)).thenReturn(List.of());
        LlmModelEntity defaultChatModel =
                new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", MODEL_KEY);
        defaultChatModel.setModelType(LlmModelEntity.TYPE_CHAT);
        when(modelRepository.findVisible(SITE_ID, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(defaultChatModel));
        chatService = new ChatService(llmClient, chatProperties,
                conversationService, requestRegistry,
                new ModelUseResolver(modelRepository,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        settingRepository, llmProperties),
                relayCredentials,
                generateMarkdownTool, generateSpreadsheetTool, generateDocxTool,
                imageGenerationTool, editImageTool,
                attachmentLoader, imageFocusResolver, Clock.systemUTC());
    }

    @Test
    void rejectsRequestWithoutUserMessageBeforeCallingTheGateway() {
        ChatCompletionRequest request = request(
                List.of(new ChatMessagePayload("system", "you are a helpful assistant")));

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> chatService.complete(request, CURRENT_USER));

        assertTrue(exception.getMessage().contains("user message"));
        verifyNoInteractions(llmClient);
    }

    @Test
    void refusesADrawingModelOnTheChatPath() {
        // visible and enabled — it is simply not a conversational model
        LlmModelEntity image = new LlmModelEntity("gpt-image-2", "GPT Image 2", "OpenAI", "gpt-image-2");
        image.setModelType(LlmModelEntity.TYPE_IMAGE);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, "gpt-image-2"))
                .thenReturn(List.of(image));

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> chatService.resolveModel("gpt-image-2", SITE_ID));

        assertTrue(exception.getMessage().contains("IMAGE"), exception.getMessage());
        assertTrue(exception.getMessage().contains("chat"), exception.getMessage());
    }

    @Test
    void neverRecordsADrawingModelAsTheConversationModel() {
        // the regression this guards: the image row resolving on the chat path, being forwarded
        // upstream and then being written into chat_messages.model_id as if it produced the answer
        LlmModelEntity image = new LlmModelEntity("gpt-image-2", "GPT Image 2", "OpenAI", "gpt-image-2");
        image.setModelType(LlmModelEntity.TYPE_IMAGE);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, "gpt-image-2"))
                .thenReturn(List.of(image));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-image-2",
                List.of(new ChatMessagePayload("user", "draw me a cat")), null, null, null, null);

        assertThrows(InvalidRequestException.class, () -> chatService.complete(request, CURRENT_USER));

        // rejected before anything is spent and before a thread is opened
        verifyNoInteractions(conversationService);
        verifyNoInteractions(llmClient);
    }

    @Test
    void rejectsCatalogueModelThatIsNotActive() {
        LlmModelEntity disabled = new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", null);
        disabled.setStatus("DISABLED");
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(disabled));

        assertThrows(NotFoundException.class, () -> chatService.resolveModel(MODEL_KEY, SITE_ID));
    }

    @Test
    void fallsBackToTheConfiguredDefaultModel() {
        LlmModelEntity entity = new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", null);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(entity));

        assertEquals(MODEL_KEY, chatService.resolveModel(null, SITE_ID).upstreamKey());
        assertEquals(MODEL_KEY, chatService.resolveModel("   ", SITE_ID).upstreamKey());
    }

    @Test
    void forwardsCatalogueRouteUpstreamWhileEchoingThePublicKey() {
        LlmModelEntity entity = new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", "deepseek-v4.1-flash-0910");
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(entity));

        ChatService.ResolvedModel model = chatService.resolveModel(MODEL_KEY, SITE_ID);

        assertEquals(MODEL_KEY, model.requestKey());
        assertEquals("deepseek-v4.1-flash-0910", model.upstreamKey());
    }

    @Test
    void doesNotAdoptAnotherSitesModelRow() {
        // the key belongs to a different site, so nothing is visible here — and unlisted keys are
        // rejected rather than forwarded raw to the gateway. The catalogue still *knows* the CHAT
        // type (the row exists, it just belongs to another site), which is what makes the refusal
        // right: treating it as "type never seen" would forward another site's key upstream.
        when(modelRepository.countByTypeForResolution("CHAT")).thenReturn(1L);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, "other-sites-model"))
                .thenReturn(List.of());

        assertThrows(NotFoundException.class,
                () -> chatService.resolveModel("other-sites-model", SITE_ID));
    }

    @Test
    void doesNotAdoptAnotherSitesRetiredRowEither() {
        // invisible rows stay invisible; allow-unlisted-models=false means no raw upstream passthrough
        when(modelRepository.countByTypeForResolution("CHAT")).thenReturn(1L);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, "other-sites-model"))
                .thenReturn(List.of());

        assertThrows(NotFoundException.class,
                () -> chatService.resolveModel("other-sites-model", SITE_ID));
    }

    @Test
    void resolvesTheRowThatBelongsToTheRequestingSite() {
        LlmModelEntity siteRow = new LlmModelEntity(MODEL_KEY, "Site Model", "Site", "site-specific-upstream");
        assignId(siteRow, 42L);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(siteRow));

        ChatService.ResolvedModel model = chatService.resolveModel(MODEL_KEY, SITE_ID);

        assertEquals(42L, model.id());
        assertEquals("site-specific-upstream", model.upstreamKey());
    }

    @Test
    void persistsExchangeAndReturnsUsage() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "pong", "stop", new LlmUsage(10, 2, 12)));

        ChatCompletionResponse response = chatService.complete(userRequest(), CURRENT_USER);

        assertEquals(CONVERSATION_ID, response.conversationId().longValue());
        assertEquals(MESSAGE_ID, response.messageId().longValue());
        assertEquals("pong", response.content());
        assertEquals(MODEL_KEY, response.model());
        assertEquals("stop", response.finishReason());
        assertEquals(12, response.usage().totalTokens().intValue());

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(MODEL_KEY, command.getValue().model());
        // Plain greetings: LANGUAGE_HINT + IMAGE_TOOLS_HINT + user (no FILE_EXPORT_HINT)
        assertEquals(3, command.getValue().messages().size());
        assertEquals("system", command.getValue().messages().get(0).role());
        assertTrue(command.getValue().messages().get(0).content().contains("默认使用中文回答"));
        assertTrue(command.getValue().messages().get(0).content().contains("不要主动罗列功能"));
        assertTrue(command.getValue().messages().get(1).content().contains("generate_image"));
        assertTrue(command.getValue().messages().stream().noneMatch(message ->
                "system".equals(message.role()) && message.content().contains("generate_docx")));

        verify(conversationService).appendUserMessage(CONVERSATION_ID, "hi", List.of());
        verify(conversationService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("pong"), isNull(), any(), eq(List.of()), isNull(),
                eq(MESSAGE_ID - 1));
    }

    @Test
    void scopesTheNewConversationToTheRequestingAccount() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "pong", "stop", LlmUsage.empty()));

        chatService.complete(userRequest(), new CurrentUser(999L, 1L, "someone-else"));

        // the owner comes from the resolved account, never from the request body
        verify(conversationService).openConversation(isNull(), eq(999L), eq(MODEL_ID), isNull());
    }

    @Test
    void recordsFailedTurnWhenTheGatewayFails() {
        stubPersistence();
        doThrow(new LlmInvocationException("Model gateway returned HTTP 502: bad gateway"))
                .when(llmClient).complete(any());

        assertThrows(LlmInvocationException.class, () -> chatService.complete(userRequest(), CURRENT_USER));

        verify(conversationService).appendAssistantFailure(
                CONVERSATION_ID, MODEL_ID, UpstreamFailureText.GENERIC_TEXT, null,
                MESSAGE_ID - 1);
        verify(conversationService, never()).appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsPersistenceWhenDisabled() {
        chatProperties.setPersistenceEnabled(false);
        LlmModelEntity model = new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", null);
        assignId(model, MODEL_ID);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(model));
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "pong", "stop", LlmUsage.empty()));

        ChatCompletionResponse response = chatService.complete(userRequest(), CURRENT_USER);

        assertNull(response.conversationId());
        assertNull(response.messageId());
        verifyNoInteractions(conversationService);
    }

    @Test
    void streamsReasoningAndContentThenCompletes() {
        stubPersistence();
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s1", null, "think", null, false));
            consumer.accept(new LlmStreamChunk("s1", "Hel", null, null, false));
            consumer.accept(new LlmStreamChunk(
                    "s1", "lo", null, "stop", false, new LlmUsage(10, 2, 12)));
            consumer.accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "hi")),
                null, null, null, null, null, true);
        chatService.stream(request, CURRENT_USER, events::add);

        assertEquals(
                List.of(ChatStreamEvent.EVENT_CREATED, ChatStreamEvent.EVENT_THINKING,
                        ChatStreamEvent.EVENT_DELTA, ChatStreamEvent.EVENT_DELTA,
                        ChatStreamEvent.EVENT_COMPLETED),
                events.stream().map(ChatStreamEvent::event).toList());
        assertEquals("think", events.get(1).data().get("content"));
        assertEquals("Hel", events.get(2).data().get("content"));
        assertEquals("Hello", events.get(4).data().get("content"));
        verify(conversationService).appendAssistantMessage(
                CONVERSATION_ID, MODEL_ID, "Hello", "think", new LlmUsage(10, 2, 12), List.of(), null,
                MESSAGE_ID - 1);
    }

    @Test
    void streamPathOffersImageToolsButOmitsFileToolsOnPlainGreeting() {
        stubPersistence();
        doAnswer(invocation -> {
            invocation.<Consumer<LlmStreamChunk>>getArgument(1).accept(
                    new LlmStreamChunk("s1", "hi", null, null, false));
            invocation.<Consumer<LlmStreamChunk>>getArgument(1).accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        chatService.stream(userRequest(), CURRENT_USER, event -> {});

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).stream(command.capture(), any());
        List<String> toolNames = command.getValue().tools().stream()
                .map(LlmToolDefinition::name)
                .toList();
        assertTrue(toolNames.contains(ImageGenerationTool.TOOL_NAME));
        assertTrue(toolNames.contains(EditImageTool.TOOL_NAME));
        assertFalse(toolNames.contains(GenerateDocxTool.TOOL_NAME));
        assertTrue(command.getValue().messages().stream().noneMatch(message ->
                "system".equals(message.role()) && message.content().contains("generate_docx")));
    }

    @Test
    void streamPathDeclaresFileToolsAndExportHintOnExportIntent() {
        stubPersistence();
        doAnswer(invocation -> {
            invocation.<Consumer<LlmStreamChunk>>getArgument(1).accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        ChatCompletionRequest export = request(List.of(
                new ChatMessagePayload("user", "帮我生成一个工资表 Excel")));
        chatService.stream(export, CURRENT_USER, event -> {});

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).stream(command.capture(), any());
        List<String> toolNames = command.getValue().tools().stream()
                .map(LlmToolDefinition::name)
                .toList();
        assertTrue(toolNames.contains(GenerateMarkdownTool.TOOL_NAME));
        assertTrue(toolNames.contains(GenerateSpreadsheetTool.TOOL_NAME));
        assertTrue(toolNames.contains(GenerateDocxTool.TOOL_NAME));
        assertTrue(command.getValue().messages().stream().anyMatch(message ->
                "system".equals(message.role())
                        && message.content().contains("红字")
                        && message.content().contains("generate_docx")
                        && message.content().contains("格式路由")));
    }

    @Test
    void completePathInjectsFileExportHintOnExportIntent() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-export", MODEL_KEY, "好的", "stop", LlmUsage.empty()));

        chatService.complete(request(List.of(
                new ChatMessagePayload("user", "导出一份 Word 报告"))), CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertTrue(command.getValue().messages().stream().anyMatch(message ->
                "system".equals(message.role())
                        && message.content().contains("generate_docx")
                        && message.content().contains("必须再次调用对应生成工具")));
    }

    @Test
    void completePathInjectsFileExportHintOnCasualWordRequest() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-export-casual", MODEL_KEY, "好的", "stop", LlmUsage.empty()));

        chatService.complete(request(List.of(
                new ChatMessagePayload("user", "我要word文档"))), CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertTrue(command.getValue().messages().stream().anyMatch(message ->
                "system".equals(message.role())
                        && message.content().contains("generate_docx")));
    }

    @Test
    void streamPathDeclaresFileToolsOnCasualWordRequest() {
        stubPersistence();
        doAnswer(invocation -> {
            invocation.<Consumer<LlmStreamChunk>>getArgument(1).accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        chatService.stream(request(List.of(
                new ChatMessagePayload("user", "我要word文档"))), CURRENT_USER, event -> {});

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).stream(command.capture(), any());
        List<String> toolNames = command.getValue().tools().stream()
                .map(LlmToolDefinition::name)
                .toList();
        assertTrue(toolNames.contains(GenerateDocxTool.TOOL_NAME));
        assertTrue(command.getValue().messages().stream().anyMatch(message ->
                "system".equals(message.role())
                        && message.content().contains("generate_docx")));
    }

    @Test
    void completePathSkipsFileExportOnAmbiguousDocumentMention() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-plain", MODEL_KEY, "好的", "stop", LlmUsage.empty()));

        chatService.complete(request(List.of(
                new ChatMessagePayload("user", "我要文档里提到的那个方法讲清楚"))), CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertTrue(command.getValue().messages().stream().noneMatch(message ->
                "system".equals(message.role()) && message.content().contains("generate_docx")));
    }

    @Test
    void artifactRevisionInjectsLatestSuccessfulToolArguments() {
        stubPersistence();
        String originalArgs = "{\"title\":\"工资表\",\"sheets\":[{\"name\":\"工资明细\"}]}";
        when(conversationService.latestArtifactContext(CONVERSATION_ID, USER_ID))
                .thenReturn(List.of(new ChatArtifactContext(
                        GenerateSpreadsheetTool.TOOL_NAME, originalArgs)));
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-revision", MODEL_KEY, "已重新生成", "stop", LlmUsage.empty()));

        ChatCompletionRequest revision = new ChatCompletionRequest(
                null, List.of(
                new ChatMessagePayload("assistant", "已生成工资表"),
                new ChatMessagePayload("user", "把名字改成粉色，其他不变")),
                null, null, CONVERSATION_ID, null);
        chatService.complete(revision, CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertTrue(command.getValue().messages().stream().anyMatch(message ->
                "system".equals(message.role())
                        && message.content().contains("最近一次成功生成")
                        && message.content().contains(GenerateSpreadsheetTool.TOOL_NAME)
                        && message.content().contains(originalArgs)));
    }

    @Test
    void unrelatedEnglishSubstringsDoNotLoadArtifactContext() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-normal", MODEL_KEY, "好的", "stop", LlmUsage.empty()));
        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload(
                "user", "Please add additional address details.")),
                null, null, CONVERSATION_ID, null);

        chatService.complete(request, CURRENT_USER);

        verify(conversationService, never()).latestArtifactContext(anyLong(), anyLong());
    }

    @Test
    void ordinaryChineseAdditionDoesNotLoadArtifactContext() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-normal", MODEL_KEY, "好的", "stop", LlmUsage.empty()));
        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "请增加一些说明，聊聊怎么写得更自然")),
                null, null, CONVERSATION_ID, null);

        chatService.complete(request, CURRENT_USER);

        verify(conversationService, never()).latestArtifactContext(anyLong(), anyLong());
    }

    @Test
    void conciseStyleCorrectionLoadsArtifactContext() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-style", MODEL_KEY, "好的", "stop", LlmUsage.empty()));
        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "不要红底")),
                null, null, CONVERSATION_ID, null);

        chatService.complete(request, CURRENT_USER);

        verify(conversationService).latestArtifactContext(CONVERSATION_ID, USER_ID);
    }

    @Test
    void successfulArtifactToolCallPersistsItsArgumentsAsRevisionBaseline() {
        stubPersistence();
        String arguments = "{\"title\":\"工资表\",\"sheets\":[{\"name\":\"工资明细\",\"table\":{\"columns\":[],\"rows\":[]}}]}";
        int[] upstreamCall = {0};
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            if (upstreamCall[0]++ == 0) {
                consumer.accept(new LlmStreamChunk(
                        "s1", null, null, null, false, null,
                        List.of(new LlmToolCallDelta(0, "call-xlsx",
                                GenerateSpreadsheetTool.TOOL_NAME, arguments))));
            } else {
                consumer.accept(new LlmStreamChunk("s2", "文件已更新", null, "stop", false));
            }
            consumer.accept(LlmStreamChunk.done("s"));
            return null;
        }).when(llmClient).stream(any(), any());
        when(generateSpreadsheetTool.execute(any(), any())).thenReturn(AgentTool.ToolOutcome.ok(
                "已生成 Excel", Map.of("files", List.of(Map.of(
                        "fileId", 901L,
                        "name", "工资表.xlsx",
                        "mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "url", "/api/v1/files/901/content",
                        "sizeBytes", 123L)))));

        chatService.stream(exportRequest(), CURRENT_USER, event -> {});

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatArtifactContext>> context = ArgumentCaptor.forClass(List.class);
        verify(conversationService).appendAssistantMessageWithArtifactContext(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("文件已更新"), isNull(), any(), any(),
                isNull(), eq(MESSAGE_ID - 1), context.capture());
        assertEquals(List.of(new ChatArtifactContext(
                GenerateSpreadsheetTool.TOOL_NAME, arguments)), context.getValue());
    }

    @Test
    void failedArtifactToolCallIsNotPersistedAsRevisionBaseline() {
        stubPersistence();
        String arguments = "{\"title\":\"无效工资表\",\"sheets\":[]}";
        int[] upstreamCall = {0};
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            if (upstreamCall[0]++ == 0) {
                consumer.accept(new LlmStreamChunk(
                        "s1", null, null, null, false, null,
                        List.of(new LlmToolCallDelta(0, "call-xlsx",
                                GenerateSpreadsheetTool.TOOL_NAME, arguments))));
            } else {
                consumer.accept(new LlmStreamChunk("s2", "生成失败，请调整数据", null, "stop", false));
            }
            consumer.accept(LlmStreamChunk.done("s"));
            return null;
        }).when(llmClient).stream(any(), any());
        when(generateSpreadsheetTool.execute(any(), any()))
                .thenReturn(AgentTool.ToolOutcome.failed("工作表不能为空"));

        chatService.stream(exportRequest(), CURRENT_USER, event -> {});

        verify(conversationService, never()).appendAssistantMessageWithArtifactContext(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any(), any());
        verify(conversationService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("生成失败，请调整数据"), isNull(), any(),
                eq(List.of()), isNull(), eq(MESSAGE_ID - 1));
    }

    @Test
    void streamsFailureFrameInsteadOfThrowing() {        stubPersistence();
        doThrow(new LlmInvocationException("Model gateway stream failed: connection reset"))
                .when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        chatService.stream(userRequest(), CURRENT_USER, events::add);

        ChatStreamEvent last = events.get(events.size() - 1);
        assertEquals(ChatStreamEvent.EVENT_FAILED, last.event());
        assertEquals(UpstreamFailureText.GENERIC_TEXT, last.data().get("message"));
        verify(conversationService).appendAssistantFailure(
                CONVERSATION_ID, MODEL_ID, UpstreamFailureText.GENERIC_TEXT, null,
                MESSAGE_ID - 1);
    }

    @Test
    void treatsThinkingOnlyStreamAsFailure() {
        stubPersistence();
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s1", null, "only thinking", null, false));
            consumer.accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        chatService.stream(userRequest(), CURRENT_USER, events::add);

        ChatStreamEvent last = events.get(events.size() - 1);
        assertEquals(ChatStreamEvent.EVENT_FAILED, last.event());
        assertEquals("模型未返回有效内容", last.data().get("message"));
        verify(conversationService).appendAssistantFailure(
                CONVERSATION_ID, MODEL_ID, "模型未返回有效内容", null, MESSAGE_ID - 1);
        verify(conversationService, never()).appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void dropsReasoningWhenDeepThinkIsOff() {
        stubPersistence();
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s1", null, "secret thoughts", null, false));
            consumer.accept(new LlmStreamChunk("s1", "answer", null, null, false));
            consumer.accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        chatService.stream(userRequest(), CURRENT_USER, events::add);

        assertTrue(events.stream().noneMatch(event ->
                ChatStreamEvent.EVENT_THINKING.equals(event.event())));
        assertEquals("answer", events.stream()
                .filter(event -> ChatStreamEvent.EVENT_DELTA.equals(event.event()))
                .map(event -> event.data().get("content"))
                .findFirst()
                .orElse(null));
        verify(conversationService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("answer"), isNull(), any(),
                eq(List.of()), isNull(), eq(MESSAGE_ID - 1));
    }

    @Test
    void keepsWhatTheClientReceivedWhenTheStreamIsStopped() {
        stubPersistence();
        List<ChatStreamEvent> events = new ArrayList<>();

        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk(
                    "s1", "Hel", null, null, false, new LlmUsage(10, 2, 12)));

            // the client presses stop, using the id it read from message.created
            String requestId = (String) events.get(0).data().get("requestId");
            chatService.cancelGeneration(requestId, USER_ID);

            // the next frame trips the cancellation check, which unwinds and closes the upstream
            consumer.accept(new LlmStreamChunk("s1", "lo", null, null, false));
            return null;
        }).when(llmClient).stream(any(), any());

        chatService.stream(userRequest(), CURRENT_USER, events::add);

        assertEquals(
                List.of(ChatStreamEvent.EVENT_CREATED, ChatStreamEvent.EVENT_DELTA,
                        ChatStreamEvent.EVENT_CANCELLED),
                events.stream().map(ChatStreamEvent::event).toList());
        // the token after the stop reached neither the client nor the stored answer
        assertEquals("Hel", events.get(2).data().get("content"));

        verify(conversationService).appendAssistantCancelled(
                CONVERSATION_ID, MODEL_ID, "Hel", null, new LlmUsage(10, 2, 12), null, MESSAGE_ID - 1);
        verify(conversationService, never()).appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void publishesTheRequestIdAClientNeedsToStopTheGeneration() {
        stubPersistence();
        doAnswer(invocation -> {
            invocation.<Consumer<LlmStreamChunk>>getArgument(1).accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        List<ChatStreamEvent> events = new ArrayList<>();
        chatService.stream(userRequest(), CURRENT_USER, events::add);

        Object requestId = events.get(0).data().get("requestId");
        assertNotNull(requestId, "message.created must carry the request id");
        assertTrue(requestId.toString().length() >= 16);
    }

    @Test
    void treatsAClientDisconnectAsAStop() {
        stubPersistence();
        doAnswer(invocation -> {
            invocation.<Consumer<LlmStreamChunk>>getArgument(1)
                    .accept(new LlmStreamChunk(
                            "s1", "Hel", null, null, false, new LlmUsage(10, 2, 12)));
            return null;
        }).when(llmClient).stream(any(), any());

        // message.created gets through, then the connection breaks - standing in for a client that
        // closed the tab mid answer
        AtomicBoolean firstFrame = new AtomicBoolean(true);
        ChatEventPublisher publisher = event -> {
            if (firstFrame.getAndSet(false)) {
                return;
            }
            throw new UncheckedIOException(new IOException("broken pipe"));
        };

        chatService.stream(userRequest(), CURRENT_USER, publisher);

        // the token that could not be delivered is not recorded as if the user had seen it
        verify(conversationService).appendAssistantCancelled(
                CONVERSATION_ID, MODEL_ID, "", null, new LlmUsage(10, 2, 12), null, MESSAGE_ID - 1);
        verify(conversationService, never()).appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsCancellingARequestThatIsNotInFlight() {
        assertThrows(NotFoundException.class, () -> chatService.cancelGeneration("ghost", USER_ID));
    }

    @Test
    void requestsThinkingOnlyWhenTheCatalogueModelDeclaresSupport() {
        stubPersistence();
        LlmModelEntity model = new LlmModelEntity(MODEL_KEY, "Reasoning model", "DeepSeek", null);
        model.setThinking(true);
        model.setCapabilitiesJson("{\"thinkingParameter\":\"enable_thinking\"}");
        assignId(model, MODEL_ID);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(model));
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "answer", "stop", LlmUsage.empty()));

        ChatCompletionRequest request = new ChatCompletionRequest(
                MODEL_KEY, List.of(new ChatMessagePayload("user", "reason this through")),
                null, null, null, null, null, true);
        chatService.complete(request, CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertTrue(command.getValue().thinking());
        assertEquals("enable_thinking", command.getValue().thinkingParameter());
    }

    @Test
    void ignoresThinkingRequestWhenCatalogueModelDoesNotSupportIt() {
        stubPersistence();
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "answer", "stop", LlmUsage.empty()));

        ChatCompletionRequest request = new ChatCompletionRequest(
                MODEL_KEY, List.of(new ChatMessagePayload("user", "reason this through")),
                null, null, null, null, null, true);
        chatService.complete(request, CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(false, command.getValue().thinking());
        assertNull(command.getValue().thinkingParameter());
    }

    @Test
    void keepsThinkingParameterWhenUserTurnsThinkingOff() {
        stubPersistence();
        LlmModelEntity model = new LlmModelEntity(MODEL_KEY, "Reasoning model", "DeepSeek", null);
        model.setThinking(true);
        model.setCapabilitiesJson(
                "{\"thinkingParameter\":\"enable_thinking\",\"thinkingOnValue\":\"true\",\"thinkingOffValue\":\"false\"}");
        assignId(model, MODEL_ID);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(model));
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "answer", "stop", LlmUsage.empty()));

        ChatCompletionRequest request = new ChatCompletionRequest(
                MODEL_KEY, List.of(new ChatMessagePayload("user", "no deep think")),
                null, null, null, null, null, false);
        chatService.complete(request, CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(false, command.getValue().thinking());
        assertEquals("enable_thinking", command.getValue().thinkingParameter());
        assertEquals("true", command.getValue().thinkingOnValue());
        assertEquals("false", command.getValue().thinkingOffValue());
    }

    @Test
    void sendsDeepSeekFamilyDefaultOffValueWhenCatalogueOmitsParameter() {
        stubPersistence();
        LlmModelEntity model = new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", null);
        model.setThinking(true);
        assignId(model, MODEL_ID);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(model));
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "answer", "stop", LlmUsage.empty()));

        ChatCompletionRequest request = new ChatCompletionRequest(
                MODEL_KEY, List.of(new ChatMessagePayload("user", "no deep think")),
                null, null, null, null, null, false);
        chatService.complete(request, CURRENT_USER);

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(false, command.getValue().thinking());
        assertEquals("reasoning_effort", command.getValue().thinkingParameter());
        assertEquals("high", command.getValue().thinkingOnValue());
        assertEquals("none", command.getValue().thinkingOffValue());
    }

    @Test
    void persistsAttachmentRefsOnTheUserMessage() {
        stubPersistence();
        ChatAttachmentRef ref = new ChatAttachmentRef(
                5L, "image/png", "shirt.png", 20480L, "/api/v1/files/5/content");
        when(attachmentLoader.refsFor(any(), eq(List.of(5L)))).thenReturn(List.of(ref));
        when(attachmentLoader.loadForNewMessage(any(), eq(List.of(5L))))
                .thenReturn(new ChatAttachmentLoader.Loaded(List.of(), List.of(ref), "", ""));
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "nice shirt", "stop", LlmUsage.empty()));

        ChatCompletionRequest request = new ChatCompletionRequest(
                null,
                List.of(new ChatMessagePayload("user", "描述这张图", List.of(5L))),
                null, null, null, null);

        chatService.complete(request, CURRENT_USER);

        verify(conversationService).appendUserMessage(CONVERSATION_ID, "描述这张图", List.of(ref));
    }

    @Test
    void rejectsRegenerateAndEditTogether() {
        stubPersistence();
        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "hi")),
                null, null, CONVERSATION_ID, null, null, null, 55L, 44L);

        InvalidRequestException exception = assertThrows(InvalidRequestException.class,
                () -> chatService.complete(request, CURRENT_USER));

        assertTrue(exception.getMessage().contains("不能同时重新生成和编辑"));
        verify(conversationService, never()).prepareRegenerate(anyLong(), anyLong(), anyLong());
        verify(conversationService, never()).prepareEdit(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(llmClient);
    }

    @Test
    void rejectsEditOrRegenerateWithoutConversationId() {
        stubPersistence();
        ChatCompletionRequest regenerate = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "hi")),
                null, null, null, null, null, null, 55L, null);
        ChatCompletionRequest edit = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "改一下")),
                null, null, null, null, null, null, null, 44L);

        assertThrows(InvalidRequestException.class,
                () -> chatService.complete(regenerate, CURRENT_USER));
        assertThrows(InvalidRequestException.class,
                () -> chatService.complete(edit, CURRENT_USER));
        verifyNoInteractions(llmClient);
    }

    @Test
    void regeneratesJoinTheSameTurnGroupWithoutANewUserRow() {
        stubPersistence();
        long assistantId = 55L;
        long turnGroupId = 88L;
        long replyToUserId = 44L;
        when(conversationService.prepareRegenerate(CONVERSATION_ID, USER_ID, assistantId))
                .thenReturn(new ConversationService.RegeneratePrep(turnGroupId, replyToUserId));

        ChatMessageEntity regenerated = mock(ChatMessageEntity.class);
        when(regenerated.getId()).thenReturn(MESSAGE_ID + 1);
        when(regenerated.getTurnGroupId()).thenReturn(turnGroupId);
        when(regenerated.getVersion()).thenReturn(2);
        when(conversationService.appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), eq(turnGroupId), eq(replyToUserId)))
                .thenReturn(regenerated);

        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s1", "v2", null, "stop", false, new LlmUsage(1, 1, 2)));
            consumer.accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "hi")),
                null, null, CONVERSATION_ID, null, null, null, assistantId, null);

        List<ChatStreamEvent> events = new ArrayList<>();
        chatService.stream(request, CURRENT_USER, events::add);

        verify(conversationService).prepareRegenerate(CONVERSATION_ID, USER_ID, assistantId);
        verify(conversationService, never()).appendUserMessage(anyLong(), anyString(), any());
        verify(conversationService, never()).appendUserMessageVersion(anyLong(), anyLong(), anyString(), any());
        verify(conversationService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("v2"), isNull(), any(), eq(List.of()),
                eq(turnGroupId), eq(replyToUserId));

        ChatStreamEvent created = events.get(0);
        assertEquals(ChatStreamEvent.EVENT_CREATED, created.event());
        assertEquals(replyToUserId, ((Number) created.data().get("userMessageId")).longValue());
        assertEquals(turnGroupId, ((Number) created.data().get("assistantTurnGroupId")).longValue());

        ChatStreamEvent completed = events.get(events.size() - 1);
        assertEquals(ChatStreamEvent.EVENT_COMPLETED, completed.event());
        assertEquals(MESSAGE_ID + 1, ((Number) completed.data().get("messageId")).longValue());
        assertEquals(turnGroupId, ((Number) completed.data().get("turnGroupId")).longValue());
        assertEquals(2, ((Number) completed.data().get("version")).intValue());
    }

    @Test
    void editsAppendAUserVersionThenAFreshAssistantTurn() {
        stubPersistence();
        long editOfUserId = 44L;
        long userTurnGroupId = 44L;
        long editedUserId = 200L;
        when(conversationService.prepareEdit(CONVERSATION_ID, USER_ID, editOfUserId))
                .thenReturn(userTurnGroupId);

        ChatMessageEntity editedUser = mock(ChatMessageEntity.class);
        when(editedUser.getId()).thenReturn(editedUserId);
        when(conversationService.appendUserMessageVersion(
                CONVERSATION_ID, userTurnGroupId, "改过的提问", List.of()))
                .thenReturn(editedUser);

        ChatMessageEntity newAssistant = mock(ChatMessageEntity.class);
        when(newAssistant.getId()).thenReturn(MESSAGE_ID + 2);
        when(newAssistant.getTurnGroupId()).thenReturn(MESSAGE_ID + 2);
        when(newAssistant.getVersion()).thenReturn(1);
        when(conversationService.appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), isNull(), eq(editedUserId)))
                .thenReturn(newAssistant);

        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", MODEL_KEY, "新回答", "stop", new LlmUsage(1, 1, 2)));

        ChatCompletionRequest request = new ChatCompletionRequest(
                null, List.of(new ChatMessagePayload("user", "改过的提问")),
                null, null, CONVERSATION_ID, null, null, null, null, editOfUserId);

        ChatCompletionResponse response = chatService.complete(request, CURRENT_USER);

        verify(conversationService).prepareEdit(CONVERSATION_ID, USER_ID, editOfUserId);
        verify(conversationService).appendUserMessageVersion(
                CONVERSATION_ID, userTurnGroupId, "改过的提问", List.of());
        verify(conversationService, never()).appendUserMessage(anyLong(), anyString(), any());
        verify(conversationService).appendAssistantMessage(
                eq(CONVERSATION_ID), eq(MODEL_ID), eq("新回答"), isNull(), any(), eq(List.of()),
                isNull(), eq(editedUserId));
        assertEquals(MESSAGE_ID + 2, response.messageId().longValue());
        assertEquals("新回答", response.content());
    }

    private ChatCompletionRequest userRequest() {
        return request(List.of(new ChatMessagePayload("user", "hi")));
    }

    private ChatCompletionRequest exportRequest() {
        return request(List.of(new ChatMessagePayload("user", "帮我生成一个工资表 Excel")));
    }

    private static ChatCompletionRequest request(List<ChatMessagePayload> messages) {
        return new ChatCompletionRequest(null, messages, null, null, null, null);
    }

    private void stubPersistence() {
        ConversationEntity conversation = mock(ConversationEntity.class);
        when(conversation.getId()).thenReturn(CONVERSATION_ID);
        when(conversationService.openConversation(any(), anyLong(), anyLong(), any())).thenReturn(conversation);

        ChatMessageEntity userMessage = mock(ChatMessageEntity.class);
        when(userMessage.getId()).thenReturn(MESSAGE_ID - 1);
        when(conversationService.appendUserMessage(anyLong(), anyString(), any())).thenReturn(userMessage);
        when(conversationService.appendUserMessageVersion(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(userMessage);

        ChatMessageEntity reply = mock(ChatMessageEntity.class);
        when(reply.getId()).thenReturn(MESSAGE_ID);
        when(reply.getTurnGroupId()).thenReturn(MESSAGE_ID);
        when(reply.getVersion()).thenReturn(1);
        when(conversationService.appendAssistantMessage(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(reply);

        LlmModelEntity model = new LlmModelEntity(MODEL_KEY, "DeepSeek V4.1 Flash", "DeepSeek", null);
        assignId(model, MODEL_ID);
        when(modelRepository.findVisibleByModelKeyForResolution(SITE_ID, MODEL_KEY))
                .thenReturn(List.of(model));
    }

    /** Entities keep their generated identifier private, so fixtures assign it reflectively. */
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
