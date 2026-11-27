package com.hub.backend.modules.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.agent.dto.AgentToolCallRecord;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.entity.AgentModelEntity;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.agent.repository.AgentModelRepository;
import com.hub.backend.modules.agent.tool.AgentTool;
import com.hub.backend.modules.agent.tool.AgentToolRegistry;
import com.hub.backend.modules.agent.tool.ToolCallCollector;
import com.hub.backend.modules.agent.tool.ToolContext;
import com.hub.backend.modules.agent.tool.WebSearchTool;
import com.hub.backend.modules.appconfig.AppConfigService;
import com.hub.backend.modules.appconfig.ModuleChatModelService;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.ChatEventPublisher;
import com.hub.backend.modules.chat.ChatRequestRegistry;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatCompletionResponse;
import com.hub.backend.modules.chat.dto.ChatMessagePayload;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelFallbackInvoker;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Runs one agent chat turn while keeping the agent transcript in its own tables.
 *
 * <p>The orchestration intentionally mirrors the generic chat service: resolve the model, persist
 * the user turn, call the gateway, then persist the assistant outcome. What differs is the first
 * system message and all persistence calls, both of which are agent specific.</p>
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /**
     * Upper bound of tool rounds inside one turn. The last round is run without declaring any
     * tool, so the model has to answer with what it already has instead of looping forever.
     */
    private static final int MAX_TOOL_STEPS = 3;

    /**
     * 最多回看多少条历史消息。
     *
     * <p>这个数是**条数**上限，不是长度上限：40 条长消息照样能把上下文撑爆，所以还有
     * {@link #MAX_CONTEXT_CHARS} 兜着。两者取先触发的那一个。</p>
     */
    private static final int MAX_CONTEXT_MESSAGES = 40;

    /**
     * 带进模型的历史字符预算（不含 system prompt 与本次用户消息）。
     *
     * <p>24000 是保守值：主流模型的上下文窗口足够，抠得松一点可以让长对话更连贯，
     * 同时又不至于把几万字一次性推给上游。真正精确的做法是按 token 计，但需要引入
     * 与模型对应的分词器，先用字符近似。</p>
     */
    private static final int MAX_CONTEXT_CHARS = 24_000;

    /**
     * 带进上下文的历史图片张数上限。
     *
     * <p>图片是上下文里最贵的部分：一张 1024x1024 的图约 1000–1500 token，而它<b>每一轮都要
     * 重发一次</b>。50 轮对话各带 2 张图的话，光历史图片就够撑爆窗口，费用也是随轮数线性增长。
     * 所以只重发最近几张，更早的只留文字并标一句「图片已省略」——让模型知道上下文不完整，
     * 好过让它以为用户从没发过图。</p>
     */
    private static final int MAX_CONTEXT_IMAGES = 6;

    private static final String OMITTED_IMAGE_MARKER = "\n[图片已省略]";

    /** 上游什么都没说（空 message、纯超时）时的兜底文案，见 {@link UpstreamFailureText}。 */
    private static final String MODEL_FAILURE_TEXT = "模型调用失败，请稍后重试";

    private final LlmClient llmClient;
    private final AppConfigService appConfigService;
    private final AgentService agentService;
    private final AgentModelRepository agentModels;
    private final LlmModelRepository models;
    private final AgentChatMessageRepository chatMessages;
    private final ModelUseResolver modelResolver;
    private final ChatRequestRegistry requestRegistry;
    private final UserRelayCredentialResolver relayCredentials;
    private final AgentToolRegistry toolRegistry;
    private final ChatAttachmentLoader attachmentLoader;
    private final ObjectMapper objectMapper;
    private final ModuleChatModelService moduleChatModels;

    /** 模型回退日志按「智能体 + 原模型 + 实际模型」去重，避免每轮对话刷一行 WARN */
    private final Set<String> warnedModelFallbacks = ConcurrentHashMap.newKeySet();

    @Autowired
    public AgentChatService(LlmClient llmClient,
                            AppConfigService appConfigService,
                            AgentService agentService,
                            AgentModelRepository agentModels,
                            LlmModelRepository models,
                            AgentChatMessageRepository chatMessages,
                            ModelUseResolver modelResolver,
                            ChatRequestRegistry requestRegistry,
                            UserRelayCredentialResolver relayCredentials,
                            AgentToolRegistry toolRegistry,
                            ChatAttachmentLoader attachmentLoader,
                            ObjectMapper objectMapper,
                            ModuleChatModelService moduleChatModels) {
        this.llmClient = llmClient;
        this.appConfigService = appConfigService;
        this.agentService = agentService;
        this.agentModels = agentModels;
        this.models = models;
        this.chatMessages = chatMessages;
        this.modelResolver = modelResolver;
        this.requestRegistry = requestRegistry;
        this.relayCredentials = relayCredentials;
        this.toolRegistry = toolRegistry;
        this.attachmentLoader = attachmentLoader;
        this.objectMapper = objectMapper;
        this.moduleChatModels = moduleChatModels;
    }

    public AgentChatService(LlmClient llmClient,
                            AppConfigService appConfigService,
                            AgentService agentService,
                            AgentModelRepository agentModels,
                            LlmModelRepository models,
                            AgentChatMessageRepository chatMessages,
                            ModelUseResolver modelResolver,
                            ChatRequestRegistry requestRegistry,
                            UserRelayCredentialResolver relayCredentials,
                            AgentToolRegistry toolRegistry,
                            ChatAttachmentLoader attachmentLoader,
                            ObjectMapper objectMapper) {
        this(llmClient, appConfigService, agentService, agentModels, models, chatMessages,
                modelResolver, requestRegistry, relayCredentials, toolRegistry, attachmentLoader,
                objectMapper, null);
    }

    /**
     * Blocking agent completion, used by {@code POST /api/v1/agents/{sn}/chat/completions}.
     */
    public ChatCompletionResponse complete(String agentSn,
                                           ChatCompletionRequest request,
                                           CurrentUser currentUser) {
        long startedAt = System.nanoTime();
        AgentEntity agent = agentService.requireVisibleBySn(agentSn, currentUser);
        String userContent = lastUserContent(request.messages());
        ResolvedAgentModel model = resolveModel(agent, request, currentUser.siteId());
        ProviderCredentials credentials = relayCredentials.resolve(currentUser, model.id());
        Turn turn = startTurn(request, currentUser, agent, model, userContent, credentials);

        LlmChatCompletion completion;
        AtomicReference<ModelUseResolver.Resolved> attemptedModel =
                new AtomicReference<>(model.primary());
        try {
            ModelFallbackInvoker.Result<LlmChatCompletion> result =
                    ModelFallbackInvoker.invoke(
                            model.candidates(),
                            candidate -> llmClient.complete(toCommand(
                                    initialMessages(agent, turn.history(),
                                            turn.userContentForModel(), turn.images()),
                                    request, candidate, credentials)),
                            attemptedModel::set);
            completion = result.value();
            attemptedModel.set(result.model());
        } catch (LlmInvocationException exception) {
            // 落库和抛给前端的都换成可读文案：上游 400 内容审核的原话是一段
            // "<what> gateway returned HTTP 400: {...}" 的英文包壳，用户看不懂，也不该看到。
            String failure = UpstreamFailureText.describe(
                    exception.getMessage(), exception.getUpstreamCode(), MODEL_FAILURE_TEXT);
            agentService.appendAssistantFailure(
                    turn.conversation().getId(), attemptedModel.get().id(), failure,
                    turn.assistantTurnGroupId(), turn.replyToMessageId());
            // 保留上游状态码与业务码：handler 据此决定回 400（内容审核，用户可改）还是 502。
            throw new LlmInvocationException(failure, exception,
                    exception.getUpstreamStatus(), exception.getUpstreamCode());
        }

        AgentChatMessageEntity reply = agentService.appendAssistantMessage(
                turn.conversation().getId(),
                attemptedModel.get().id(),
                completion.contentOrEmpty(),
                null,
                completion.usage(),
                completion.id(),
                List.of(),
                turn.assistantTurnGroupId(),
                turn.replyToMessageId());
        agentService.registerUse(agent.getId());
        var usage = completion.usage();
        return new ChatCompletionResponse(
                turn.conversation().getId(),
                reply.getId(),
                attemptedModel.get().requestKey(),
                completion.contentOrEmpty(),
                completion.finishReason(),
                ChatCompletionResponse.usageOf(
                        usage.promptTokensOrZero(),
                        usage.completionTokensOrZero(),
                        usage.totalTokensOrZero()),
                (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * Streaming agent completion, used by {@code POST /api/v1/agents/{sn}/chat/stream}.
     */
    public void stream(String agentSn,
                       ChatCompletionRequest request,
                       CurrentUser currentUser,
                       ChatEventPublisher publisher) {
        stream(prepare(agentSn, request, currentUser), publisher);
    }

    public PreparedAgentChat prepare(String agentSn,
                              ChatCompletionRequest request,
                              CurrentUser currentUser) {
        return prepare(agentSn, request, currentUser, null);
    }

    /**
     * Same as {@link #prepare(String, ChatCompletionRequest, CurrentUser)} but appends optional
     * knowledge-base context to the agent system prompt for this turn only.
     * Bills the end user (default for product agents).
     */
    public PreparedAgentChat prepare(String agentSn,
                              ChatCompletionRequest request,
                              CurrentUser currentUser,
                              String knowledgeAppendix) {
        return prepare(agentSn, request, currentUser, knowledgeAppendix, true, null, null);
    }

    /**
     * @param billToUser {@code true} 走用户网关钥匙并扣用户额度；
     *                   {@code false} 走管理端配置的平台基地址 + API Key（站内客服等）
     * @param platformBaseUrl {@code billToUser=false} 时必填的上游基地址
     * @param platformApiKey {@code billToUser=false} 时必填的平台 API Key
     */
    public PreparedAgentChat prepare(String agentSn,
                              ChatCompletionRequest request,
                              CurrentUser currentUser,
                              String knowledgeAppendix,
                              boolean billToUser,
                              String platformBaseUrl,
                              String platformApiKey) {
        AgentEntity agent = agentService.requireVisibleBySn(agentSn, currentUser);
        String userContent = lastUserContent(request.messages());
        ResolvedAgentModel model = resolveModel(agent, request, currentUser.siteId());
        ProviderCredentials credentials = billToUser
                ? relayCredentials.resolve(currentUser, model.id())
                : relayCredentials.resolvePlatform(platformBaseUrl, platformApiKey);
        return new PreparedAgentChat(
                agentSn, request, currentUser, agent, userContent, model, credentials, knowledgeAppendix);
    }

    public void stream(PreparedAgentChat prepared, ChatEventPublisher publisher) {
        ChatCompletionRequest request = prepared.request();
        CurrentUser currentUser = prepared.currentUser();
        AgentEntity agent = prepared.agent();
        ResolvedAgentModel model = prepared.model();
        Turn turn = startTurn(prepared.request(), currentUser, agent, model,
                prepared.userContent(), prepared.credentials());
        AgentConversationEntity conversation = turn.conversation();

        String requestId = requestRegistry.newRequestId();
        ChatRequestRegistry.Entry entry = requestRegistry.register(currentUser.userId(), requestId);
        String outcome = ChatRequestRegistry.OUTCOME_COMPLETED;

        publisher.publish(ChatStreamEvent.created(
                requestId, conversation.getId(), null, model.requestKey()));

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        AtomicReference<LlmUsage> usage = new AtomicReference<>(LlmUsage.empty());
        AtomicReference<LlmUsage> attemptUsage = new AtomicReference<>(LlmUsage.empty());
        // 本轮跑过的工具，收尾时随正文一起落库 —— 否则刷新后没人知道这张图是模型生成的
        List<AgentToolCallRecord> executedTools = new ArrayList<>();
        List<LlmChatMessage> messages = initialMessages(
                agent, turn.history(), turn.userContentForModel(), turn.images(),
                prepared.knowledgeAppendix());
        List<ModelUseResolver.Resolved> candidates = model.candidates();
        AtomicReference<ModelUseResolver.Resolved> attemptedModel =
                new AtomicReference<>(model.primary());
        // 能力即白名单：同一份解析结果既决定模型能看到什么，也决定服务端真正能执行什么。
        // 不能在执行阶段重新查询全局注册表，否则上游伪造一个未声明的工具名就能绕过白名单。
        // web_search 走上游原生内置工具，不再走本站 Bocha/Tavily 函数工具。
        List<String> abilityKeys = agentService.abilityKeysOf(agent.getId());
        boolean wantNativeWebSearch = abilityKeys.contains(WebSearchTool.TOOL_NAME);
        if (wantNativeWebSearch
                && candidates.stream().noneMatch(ModelUseResolver.Resolved::supportsWebSearch)) {
            outcome = ChatRequestRegistry.OUTCOME_FAILED;
            String message = "当前模型不支持联网搜索，请更换支持联网的模型或关闭该智能体的联网能力";
            publishQuietly(publisher, ChatStreamEvent.failed(message));
            requestRegistry.finish(currentUser.userId(), requestId, outcome);
            return;
        }
        List<AgentTool> declaredTools = toolRegistry.resolve(abilityKeys).stream()
                .filter(tool -> !WebSearchTool.TOOL_NAME.equals(tool.definition().name()))
                .toList();
        try {
            // 每轮都是一次完整的流式补全：模型要么输出正文、要么请求工具。
            // 最后一轮不再声明工具，逼它用已有结果收尾，避免来回调用停不下来。
            for (int step = 0; step <= MAX_TOOL_STEPS; step += 1) {
                List<AgentTool> offeredTools = step == MAX_TOOL_STEPS
                        ? List.of()
                        : declaredTools;
                List<LlmToolDefinition> offered = offeredTools.stream()
                        .map(AgentTool::definition)
                        .toList();
                boolean offerBuiltInWeb = step < MAX_TOOL_STEPS && wantNativeWebSearch;
                attemptUsage.set(LlmUsage.empty());
                ModelFallbackInvoker.Result<ToolCallCollector> attempt =
                        ModelFallbackInvoker.stream(
                                candidates,
                                (candidate, markPublished) -> {
                                    ToolCallCollector collector = new ToolCallCollector();
                                    List<String> builtIns = offerBuiltInWeb && candidate.supportsWebSearch()
                                            ? List.of(LlmChatCommand.BUILTIN_WEB_SEARCH)
                                            : List.of();
                                    llmClient.stream(
                                            toCommand(candidate, messages, request, prepared.credentials(),
                                                    offered, builtIns),
                                            chunk -> {
                                                if (entry.isCancelled()) {
                                                    throw new AgentGenerationCancelledException(requestId);
                                                }
                                                if (chunk.usage() != null) {
                                                    attemptUsage.updateAndGet(
                                                            current -> current.merge(chunk.usage()));
                                                }
                                                if (chunk.done()) {
                                                    return;
                                                }
                                                if (chunk.hasReasoning()) {
                                                    markPublished.run();
                                                    thinking.append(chunk.reasoningDelta());
                                                    publisher.publish(ChatStreamEvent.thinking(
                                                            chunk.reasoningDelta()));
                                                }
                                                if (chunk.hasContent()) {
                                                    markPublished.run();
                                                    publisher.publish(ChatStreamEvent.delta(
                                                            chunk.contentDelta()));
                                                    answer.append(chunk.contentDelta());
                                                }
                                                if (chunk.hasToolCalls()) {
                                                    markPublished.run();
                                                    collector.accept(chunk.toolCallDeltas());
                                                }
                                            });
                                    return collector;
                                },
                                attemptedModel::set);
                int selectedIndex = candidates.indexOf(attempt.model());
                candidates = candidates.subList(selectedIndex, candidates.size());
                model = new ResolvedAgentModel(candidates);
                attemptedModel.set(attempt.model());
                usage.updateAndGet(current -> current.merge(attemptUsage.get()));
                attemptUsage.set(LlmUsage.empty());
                ToolCallCollector collector = attempt.value();

                if (!collector.hasCalls()) {
                    break;
                }

                List<LlmToolCall> calls = collector.calls();
                messages.add(LlmChatMessage.assistantToolCalls(calls));
                for (LlmToolCall call : calls) {
                    publishQuietly(publisher,
                            ChatStreamEvent.toolCall(call.id(), call.name(), call.arguments()));
                    AgentTool.ToolOutcome toolOutcome =
                            runTool(call, currentUser, turn.images(), offeredTools);
                    executedTools.add(new AgentToolCallRecord(
                            call.id(), call.name(), toolOutcome.success(),
                            toolOutcome.content(), toolOutcome.meta()));
                    messages.add(LlmChatMessage.toolResult(call.id(), toolOutcome.content()));
                    publishQuietly(publisher, ChatStreamEvent.toolResult(
                            call.id(), call.name(), toolOutcome.success(),
                            toolOutcome.content(), toolOutcome.meta()));
                }
            }
        } catch (AgentGenerationCancelledException cancelled) {
            outcome = ChatRequestRegistry.OUTCOME_CANCELLED;
            usage.updateAndGet(current -> current.merge(attemptUsage.get()));
            stopGeneration(conversation, attemptedModel.get(), answer.toString(),
                    thinking.isEmpty() ? null : thinking.toString(),
                    ChatStreamEvent.cancelled(answer.toString()), usage.get(), executedTools,
                    turn.assistantTurnGroupId(), turn.replyToMessageId(),
                    publisher, "cancelled by request " + requestId);
            return;
        } catch (LlmInvocationException exception) {
            outcome = ChatRequestRegistry.OUTCOME_FAILED;
            usage.updateAndGet(current -> current.merge(attemptUsage.get()));
            log.warn("Streaming agent turn failed: {}", exception.getMessage());
            String failure = UpstreamFailureText.describe(
                    exception.getMessage(), exception.getUpstreamCode(), MODEL_FAILURE_TEXT);
            agentService.appendAssistantFailure(
                    conversation.getId(), attemptedModel.get().id(), failure,
                    turn.assistantTurnGroupId(), turn.replyToMessageId());
            publishQuietly(publisher, ChatStreamEvent.failed(failure));
            return;
        } catch (RuntimeException deliveryFailure) {
            outcome = ChatRequestRegistry.OUTCOME_CANCELLED;
            usage.updateAndGet(current -> current.merge(attemptUsage.get()));
            stopGeneration(conversation, attemptedModel.get(), answer.toString(),
                    thinking.isEmpty() ? null : thinking.toString(), null,
                    usage.get(), executedTools,
                    turn.assistantTurnGroupId(), turn.replyToMessageId(), publisher,
                    "client disconnected: " + deliveryFailure.getMessage());
            return;
        } finally {
            requestRegistry.finish(currentUser.userId(), requestId, outcome);
        }

        agentService.appendAssistantMessage(
                conversation.getId(),
                attemptedModel.get().id(),
                answer.toString(),
                thinking.isEmpty() ? null : thinking.toString(),
                usage.get(),
                null,
                executedTools,
                turn.assistantTurnGroupId(),
                turn.replyToMessageId());
        agentService.registerUse(agent.getId());
        publisher.publish(ChatStreamEvent.completed(answer.toString()));
    }

    public void cancelGeneration(String requestId, Long userId) {
        ChatRequestRegistry.CancelOutcome outcome = requestRegistry.cancel(userId, requestId);
        if (outcome == ChatRequestRegistry.CancelOutcome.NOT_FOUND) {
            throw new NotFoundException("Request '%s' is not in flight".formatted(requestId));
        }
        log.debug("Agent generation '{}' stop requested, outcome {}", requestId, outcome);
    }

    /**
     * 开启（或复用）会话、取出本轮之前的历史、落库本次用户消息。
     *
     * <p><b>顺序很关键</b>：历史必须在写入本次用户消息**之前**读，否则刚存进去的那条会被
     * 当成历史，和下一条 user 消息重复。</p>
     */
    private Turn startTurn(ChatCompletionRequest request,
                           CurrentUser currentUser,
                           AgentEntity agent,
                           ResolvedAgentModel model,
                           String userContent,
                           ProviderCredentials credentials) {
        // 先读附件再开会话：校验失败时不该留下一个空会话
        ChatAttachmentLoader.Loaded attachments =
                loadTurnAttachments(request, currentUser, credentials, agent);
        String persistedContent = attachments.hasAudioTranscript()
                ? userContent + "\n\n[录音转写]\n" + attachments.audioTranscript()
                : userContent;
        String contentForModel = mergeDocumentText(persistedContent, attachments);
        AgentConversationEntity conversation = agentService.openConversation(
                request.conversationId(),
                currentUser.userId(),
                agent.getId(),
                model.id(),
                request.title());

        Long regenerateOf = request.regenerateOfMessageId();
        Long editOf = request.editOfMessageId();
        if (regenerateOf != null && editOf != null) {
            throw new InvalidRequestException(ApiMessages.CHAT_EDIT_AND_REGENERATE);
        }
        if ((regenerateOf != null || editOf != null) && request.conversationId() == null) {
            throw new InvalidRequestException(ApiMessages.CHAT_EDIT_REQUIRES_CONVERSATION);
        }

        List<LlmChatMessage> history = historyBeforeThisTurn(request, conversation, currentUser);

        if (regenerateOf != null) {
            // 重新生成：用户消息不动，新答案进原答案那一组。历史已经按 active 取过，
            // 这里把被替换的旧答案停用后再写新的。
            AgentService.RegeneratePrep prep = agentService.prepareRegenerate(
                    conversation.getId(), currentUser.userId(), regenerateOf);
            return new Turn(conversation, history, attachments.images(), contentForModel,
                    prep.turnGroupId(), prep.replyToMessageId());
        }
        if (editOf != null) {
            // 编辑：新问题进原问题那一组，旧问题与它后面所有轮次停用。
            Long turnGroupId = agentService.prepareEdit(
                    conversation.getId(), currentUser.userId(), editOf);
            AgentChatMessageEntity userMessage = agentService.appendUserMessageVersion(
                    conversation.getId(), turnGroupId, persistedContent, attachments.refs());
            return new Turn(conversation, history, attachments.images(), contentForModel,
                    null, userMessage.getId());
        }

        AgentChatMessageEntity userMessage = agentService.appendUserMessage(
                conversation.getId(), persistedContent, attachments.refs());
        // 落库失败时只丢配对信息，不因此让整轮失败：answer 照样能写，只是切版本时
        // 找不到配对的 user 消息，退化成按序号推断。
        return new Turn(conversation, history, attachments.images(), contentForModel,
                null, userMessage == null ? null : userMessage.getId());
    }

    /** 文本附件展开进 user 正文；图片仍走多模态，不进这段字符串 */
    private static String mergeDocumentText(String userContent, ChatAttachmentLoader.Loaded attachments) {
        String base = userContent == null ? "" : userContent;
        if (!attachments.hasDocumentText()) {
            return base;
        }
        if (base.isBlank()) {
            return attachments.documentText();
        }
        return base + "\n\n" + attachments.documentText();
    }

    /** 附件只认最后一条 user 消息：历史里那些图由服务端从库中重建，不信任客户端回传 */
    private ChatAttachmentLoader.Loaded loadTurnAttachments(ChatCompletionRequest request,
                                                            CurrentUser currentUser,
                                                            ProviderCredentials credentials,
                                                            AgentEntity agent) {
        for (int index = request.messages().size() - 1; index >= 0; index -= 1) {
            ChatMessagePayload candidate = request.messages().get(index);
            if (LlmChatMessage.ROLE_USER.equals(candidate.role())) {
                return attachmentLoader.loadForNewMessage(
                        currentUser, candidate.attachmentIds(), credentials,
                        acceptedAttachmentTypes(agent));
            }
        }
        return ChatAttachmentLoader.Loaded.empty();
    }

    private Set<String> acceptedAttachmentTypes(AgentEntity agent) {
        Set<String> accepted = new LinkedHashSet<>();
        String config = agent.getUiConfigJson();
        if (config != null && !config.isBlank()) {
            try {
                JsonNode fields = objectMapper.readTree(config).path("fields");
                if (fields.isArray()) {
                    for (JsonNode field : fields) {
                        if ("upload".equalsIgnoreCase(field.path("type").asText())) {
                            String accept = field.path("accept").asText("image/*");
                            for (String token : accept.split(",")) {
                                if (!token.isBlank()) accepted.add(token.strip());
                            }
                        }
                    }
                }
            } catch (Exception malformedConfig) {
                throw new InvalidRequestException("智能体附件配置无效");
            }
        }
        if (accepted.isEmpty()) accepted.add("image/*");
        return accepted;
    }

    /**
     * 本轮之前的上下文。
     *
     * <p>优先读库：会话 ID 一经确定，历史就是服务端的事实，客户端只负责发这一句话。
     * 这解决了三件事 —— 刷新页面不丢上下文、请求不再受 100 条上限约束、客户端无法伪造历史。</p>
     *
     * <p>读不到（新建会话，或历史全部失败被过滤）时回落到请求体里的 messages，
     * 这样老客户端继续按「带全量历史」的方式调用也不会突然失忆。</p>
     */
    private List<LlmChatMessage> historyBeforeThisTurn(ChatCompletionRequest request,
                                                       AgentConversationEntity conversation,
                                                       CurrentUser currentUser) {
        List<LlmChatMessage> stored = recentStoredMessages(conversation.getId(), currentUser);
        return stored.isEmpty() ? legacyHistory(request.messages()) : stored;
    }

    private List<LlmChatMessage> recentStoredMessages(Long conversationId, CurrentUser currentUser) {
        // 只取活跃版本：编辑或重新生成会在库里留下被停用的旧版本（供 1/N 切换），
        // 它们绝不能进上下文 —— 否则"编辑"对模型等于没发生。
        List<AgentChatMessageEntity> rows = chatMessages
                .findByAgentConversationIdAndActiveTrueAndStatusOrderBySequenceNoDesc(
                        conversationId,
                        AgentChatMessageEntity.STATUS_COMPLETED,
                        PageRequest.of(0, MAX_CONTEXT_MESSAGES));
        // 查询是倒序的（最新在前），正好可以从新往旧分配图片预算，最后再反转成正序
        List<LlmChatMessage> newestFirst = new ArrayList<>(rows.size());
        int imagesLeft = MAX_CONTEXT_IMAGES;
        for (AgentChatMessageEntity row : rows) {
            if (!isConversationalRole(row.getRole())) {
                continue;
            }
            String content = row.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            List<ChatAttachmentRef> refs = agentService.attachmentsOf(row);
            List<LlmImageAttachment> images = List.of();
            if (!refs.isEmpty()) {
                if (refs.size() <= imagesLeft) {
                    images = attachmentLoader.loadForHistory(currentUser, refs);
                    imagesLeft -= images.size();
                } else {
                    content = content + OMITTED_IMAGE_MARKER;
                }
            }
            newestFirst.add(new LlmChatMessage(row.getRole(), content, images));
        }
        Collections.reverse(newestFirst);
        return trimToBudget(newestFirst);
    }

    /** 只有 user / assistant 能进上下文；system 由智能体配置决定，tool 结果我们不落库 */
    private static boolean isConversationalRole(String role) {
        return LlmChatMessage.ROLE_USER.equals(role) || LlmChatMessage.ROLE_ASSISTANT.equals(role);
    }

    /**
     * 兼容路径：把请求体里的历史当成上下文。
     *
     * <p>最后一条通常是本次的用户消息（服务端已经单独追加），这里要丢掉，否则会重复。</p>
     */
    static List<LlmChatMessage> legacyHistory(List<ChatMessagePayload> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return List.of();
        }
        int end = incoming.size();
        if (LlmChatMessage.ROLE_USER.equals(incoming.get(end - 1).role())) {
            end -= 1;
        }
        List<LlmChatMessage> history = new ArrayList<>(end);
        for (int index = 0; index < end; index++) {
            ChatMessagePayload message = incoming.get(index);
            if (!isConversationalRole(message.role())) {
                continue;
            }
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            history.add(new LlmChatMessage(message.role(), message.content()));
        }
        return trimToBudget(history);
    }

    /**
     * 从新往旧累加，超出字符预算就丢掉更早的。
     *
     * <p>至少保留最新一条 —— 哪怕它自己就超预算，也好过给模型一个空上下文。</p>
     */
    static List<LlmChatMessage> trimToBudget(List<LlmChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int total = 0;
        int start = history.size();
        for (int index = history.size() - 1; index >= 0; index -= 1) {
            LlmChatMessage message = history.get(index);
            int length = message.content() == null ? 0 : message.content().length();
            if (total + length > MAX_CONTEXT_CHARS && start < history.size()) {
                break;
            }
            total += length;
            start = index;
        }
        if (start > 0) {
            log.debug("Trimmed {} older messages from the agent context ({} chars kept)",
                    start, total);
        }
        return List.copyOf(history.subList(start, history.size()));
    }

    /** 首轮消息：智能体的 system prompt（可附加知识库） + 服务端重建的历史 + 本次用户消息 */
    private List<LlmChatMessage> initialMessages(AgentEntity agent,
                                                 List<LlmChatMessage> history,
                                                 String userContent,
                                                 List<LlmImageAttachment> images) {
        return initialMessages(agent, history, userContent, images, null);
    }

    private List<LlmChatMessage> initialMessages(AgentEntity agent,
                                                 List<LlmChatMessage> history,
                                                 String userContent,
                                                 List<LlmImageAttachment> images,
                                                 String knowledgeAppendix) {
        List<LlmChatMessage> messages = new ArrayList<>(history.size() + 2);
        String system = joinSystemPrompt(agent.getSystemPrompt(), knowledgeAppendix);
        if (system != null && !system.isBlank()) {
            messages.add(LlmChatMessage.system(system));
        }
        messages.addAll(history);
        // 正文可以为空：只发图不发字是合法的一次提问
        messages.add(new LlmChatMessage(
                LlmChatMessage.ROLE_USER,
                userContent == null ? "" : userContent,
                images));
        return messages;
    }

    private static String joinSystemPrompt(String base, String appendix) {
        boolean hasBase = base != null && !base.isBlank();
        boolean hasAppendix = appendix != null && !appendix.isBlank();
        if (hasBase && hasAppendix) {
            return base.strip() + "\n\n" + appendix.strip();
        }
        if (hasBase) {
            return base;
        }
        if (hasAppendix) {
            return appendix;
        }
        return null;
    }

    private LlmChatCommand toCommand(List<LlmChatMessage> messages,
                                     ChatCompletionRequest request,
                                     ModelUseResolver.Resolved model,
                                     ProviderCredentials credentials) {
        return new LlmChatCommand(
                model.upstreamKey(),
                messages,
                request.temperature(),
                request.maxTokens(),
                credentials);
    }

    private LlmChatCommand toCommand(ModelUseResolver.Resolved model,
                                     List<LlmChatMessage> messages,
                                     ChatCompletionRequest request,
                                     ProviderCredentials credentials,
                                     List<LlmToolDefinition> tools) {
        return toCommand(model, messages, request, credentials, tools, List.of());
    }

    private LlmChatCommand toCommand(ModelUseResolver.Resolved model,
                                     List<LlmChatMessage> messages,
                                     ChatCompletionRequest request,
                                     ProviderCredentials credentials,
                                     List<LlmToolDefinition> tools,
                                     List<String> builtInTools) {
        return new LlmChatCommand(
                model.upstreamKey(),
                messages,
                request.temperature(),
                request.maxTokens(),
                credentials,
                tools,
                builtInTools,
                false,
                null,
                null,
                null);
    }

    /**
     * 工具不允许把整轮对话拖垮：任何异常都折算成一条失败结果交回模型解释。
     *
     * <p>{@code images} 是本轮用户上传的图（{@link Turn#images()}）—— 模型自己调不了「把这张图
     * 换背景」这类事，图必须由服务端送进工具上下文。</p>
     */
    private AgentTool.ToolOutcome runTool(LlmToolCall call,
                                          CurrentUser currentUser,
                                           List<LlmImageAttachment> images,
                                           List<AgentTool> allowedTools) {
        ToolContext context = new ToolContext(currentUser, images);
        return allowedTools.stream()
                .filter(tool -> tool.definition().name().equals(call.name()))
                .findFirst()
                .map(tool -> {
                    try {
                        return tool.execute(call, context);
                    } catch (RuntimeException unexpected) {
                        log.warn("Tool '{}' threw: {}", call.name(), unexpected.getMessage());
                        return AgentTool.ToolOutcome.failed("工具执行异常：" + unexpected.getMessage());
                    }
                })
                .orElseGet(() -> AgentTool.ToolOutcome.failed("未授权的工具调用：" + call.name()));
    }

    private ResolvedAgentModel resolveModel(AgentEntity agent, ChatCompletionRequest request, Long siteId) {
        String requestedModel = request.model();
        if (requestedModel == null || requestedModel.isBlank()) {
            String module = AgentEntity.SN_SUPER_AGENT.equals(agent.getSn())
                    ? ModuleChatModelService.SUPER_AGENT
                    : ModuleChatModelService.AI_EDUCATION.equals(request.workspace())
                    ? ModuleChatModelService.AI_EDUCATION : null;
            if (module != null) {
                if (moduleChatModels == null) {
                    throw new IllegalStateException("ModuleChatModelService is required for module defaults");
                }
                return new ResolvedAgentModel(moduleChatModels.resolveAllForSite(siteId, module));
            }
        }
        List<AgentModelEntity> configured = agentModels.findByAgentIdOrderBySortOrderAscIdAsc(agent.getId());
        String siteDefaultModel = appConfigService.defaultModelForSite(siteId);
        String modelKey;
        // 已经放弃"智能体自己配的模型"改用站点默认时，不能再要求解析结果出现在配置清单里，
        // 否则刚回退出来的可用模型会被下面的 enabled 检查再挡一次。
        boolean siteDefaultUsed = false;

        if (requestedModel == null || requestedModel.isBlank()) {
            Long defaultModelId = configured.stream()
                    .filter(AgentModelEntity::isDefaultModel)
                    .map(AgentModelEntity::getModelId)
                    .findFirst()
                    .orElse(null);
            if (defaultModelId != null) {
                Optional<LlmModelEntity> preferred = usableConfiguredModel(defaultModelId, siteId);
                if (preferred.isPresent()) {
                    modelKey = preferred.get().getModelKey();
                } else {
                    // 配的默认模型被下架/停用，不要让整轮对话 404：先在该智能体的其它模型里找，
                    // 都不行再退到站点默认。运营退役一个模型不该等于把智能体一起下线。
                    Optional<LlmModelEntity> substitute = configured.stream()
                            .filter(row -> !Objects.equals(row.getModelId(), defaultModelId))
                            .map(row -> usableConfiguredModel(row.getModelId(), siteId))
                            .flatMap(Optional::stream)
                            .findFirst();
                    modelKey = substitute.map(LlmModelEntity::getModelKey).orElse(siteDefaultModel);
                    siteDefaultUsed = substitute.isEmpty();
                    warnAgentModelFallback(agent.getId(), defaultModelId, modelKey);
                }
            } else {
                modelKey = siteDefaultModel;
            }
        } else {
            modelKey = requestedModel.strip();
            Optional<Long> requestedId = parseLong(modelKey);
            if (requestedId.isPresent()
                    && configured.stream().anyMatch(row -> requestedId.get().equals(row.getModelId()))) {
                modelKey = requireConfiguredModel(requestedId.get(), siteId).getModelKey();
            }
        }

        List<ModelUseResolver.Resolved> resolved = modelResolver.resolveAllForSite(
                siteId, modelKey, LlmModelEntity.TYPE_CHAT);
        if (!siteDefaultUsed && !configured.isEmpty()) {
            Set<Long> configuredIds = configured.stream()
                    .map(AgentModelEntity::getModelId)
                    .collect(Collectors.toSet());
            boolean enabled = resolved.stream()
                    .map(ModelUseResolver.Resolved::id)
                    .filter(Objects::nonNull)
                    .anyMatch(configuredIds::contains);
            if (!enabled) {
                throw new InvalidRequestException(
                        "Model '%s' is not enabled for this agent".formatted(modelKey));
            }
        }
        return new ResolvedAgentModel(resolved);
    }

    /**
     * One {@code agent_models} row → a model that can actually be called.
     *
     * <p>Returns empty instead of throwing for the "was disabled / retired / deleted" cases so the
     * caller can decide between falling back and failing. A wrong <em>type</em> still throws: that
     * is a configuration mistake an operator has to fix, and silently running a drawing model as a
     * chat model would hide it.</p>
     */
    private Optional<LlmModelEntity> usableConfiguredModel(Long modelId, Long siteId) {
        return models.findById(modelId)
                .filter(model -> model.getSiteId() == null || Objects.equals(model.getSiteId(), siteId))
                .map(model -> {
                    if (!LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType())) {
                        throw new InvalidRequestException(
                                "Model '%s' is not a chat model".formatted(model.getModelKey()));
                    }
                    return model;
                })
                .filter(model -> LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus()))
                .filter(LlmModelEntity::isUpstreamAvailable);
    }

    private void warnAgentModelFallback(Long agentId, Long retiredModelId, String usedKey) {
        String signature = agentId + "|" + retiredModelId + "|" + usedKey;
        if (warnedModelFallbacks.add(signature)) {
            log.warn("智能体 {} 配置的默认模型（id={}）已下架或未启用，本轮改用 '{}'；"
                            + "请在后台重新指定该智能体的默认模型",
                    agentId, retiredModelId, usedKey);
        }
    }

    private LlmModelEntity requireConfiguredModel(Long modelId, Long siteId) {
        return usableConfiguredModel(modelId, siteId).orElseThrow(() -> new NotFoundException(
                "Model %d was not found or is not available".formatted(modelId)));
    }

    private void stopGeneration(AgentConversationEntity conversation,
                                ModelUseResolver.Resolved model,
                                String partialContent,
                                String thinkingContent,
                                ChatStreamEvent frame,
                                LlmUsage usage,
                                List<AgentToolCallRecord> executedTools,
                                Long assistantTurnGroupId,
                                Long replyToMessageId,
                                ChatEventPublisher publisher,
                                String reason) {
        log.info("Agent generation stopped for conversation {} ({})", conversation.getId(), reason);
        agentService.appendAssistantCancelled(
                conversation.getId(), model.id(), partialContent, thinkingContent, usage,
                executedTools, assistantTurnGroupId, replyToMessageId);
        if (frame != null) {
            publishQuietly(publisher, frame);
        }
    }

    private void publishQuietly(ChatEventPublisher publisher, ChatStreamEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException ignored) {
            log.debug("Dropped {} frame: the client is no longer listening", event.event());
        }
    }

    private static String lastUserContent(List<ChatMessagePayload> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessagePayload candidate = messages.get(index);
            if (LlmChatMessage.ROLE_USER.equals(candidate.role())) {
                return candidate.content();
            }
        }
        throw new InvalidRequestException("messages must contain at least one user message");
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private record ResolvedAgentModel(List<ModelUseResolver.Resolved> candidates) {

        private ResolvedAgentModel {
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("At least one agent model candidate is required");
            }
            candidates = List.copyOf(candidates);
        }

        private ModelUseResolver.Resolved primary() {
            return candidates.get(0);
        }

        private Long id() {
            return primary().id();
        }

        private String requestKey() {
            return primary().requestKey();
        }
    }

    public record PreparedAgentChat(String agentSn,
                             ChatCompletionRequest request,
                             CurrentUser currentUser,
                             AgentEntity agent,
                             String userContent,
                             ResolvedAgentModel model,
                             ProviderCredentials credentials,
                             String knowledgeAppendix) {
        public PreparedAgentChat {
            knowledgeAppendix = knowledgeAppendix == null || knowledgeAppendix.isBlank()
                    ? null
                    : knowledgeAppendix;
        }
    }

    /**
     * 一轮对话的开始状态：会话 + 本轮之前的历史（不含本次用户消息）
     * + 本轮新带的图片 + 已拼好文本附件的 user 正文。
     */
    /**
     * 一轮对话的上下文。
     *
     * @param assistantTurnGroupId 新答案要加入的版本组；{@code null} 表示这是新的一轮，答案自己开组
     * @param replyToMessageId     新答案回答的用户消息；普通一轮也要记，编辑切换时靠它配对
     */
    private record Turn(AgentConversationEntity conversation,
                        List<LlmChatMessage> history,
                        List<LlmImageAttachment> images,
                        String userContentForModel,
                        Long assistantTurnGroupId,
                        Long replyToMessageId) {
    }

    /**
     * 把流式到达的工具调用碎片按 index 拼回完整调用。
     *
     * <p>首片带 id 与 name，后续只带 arguments 的一段 JSON 片段；一帧里可能出现多个 index，
     * 对应模型并行请求的多个工具。</p>
     */
    private static final class AgentGenerationCancelledException extends RuntimeException {

        private AgentGenerationCancelledException(String requestId) {
            super("Agent generation '%s' was cancelled".formatted(requestId));
        }
    }
}
