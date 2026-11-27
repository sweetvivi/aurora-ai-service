package com.hub.backend.modules.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.agent.dto.AgentCategoryResponse;
import com.hub.backend.modules.agent.dto.AgentChatMessageCursorResponse;
import com.hub.backend.modules.agent.dto.AgentChatMessageResponse;
import com.hub.backend.modules.agent.dto.AgentConversationSummaryResponse;
import com.hub.backend.modules.agent.dto.AgentFavoriteResponse;
import com.hub.backend.modules.agent.dto.AgentAdminRequest;
import com.hub.backend.modules.agent.dto.AgentModelOptionResponse;
import com.hub.backend.modules.agent.dto.AgentResponse;
import com.hub.backend.modules.agent.dto.AgentToolCallRecord;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.modules.agent.dto.AgentUpsertRequest;
import com.hub.backend.modules.agent.dto.AgentUserRequest;
import com.hub.backend.modules.agent.entity.AgentAbilityEntity;
import com.hub.backend.modules.agent.entity.AgentCategoryEntity;
import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.entity.AgentFavoriteEntity;
import com.hub.backend.modules.agent.entity.AgentModelEntity;
import com.hub.backend.modules.agent.entity.AgentQuestionEntity;
import com.hub.backend.modules.agent.entity.AgentTagEntity;
import com.hub.backend.modules.agent.repository.AgentAbilityRepository;
import com.hub.backend.modules.agent.repository.AgentCategoryRepository;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.agent.repository.AgentConversationRepository;
import com.hub.backend.modules.agent.repository.AgentFavoriteRepository;
import com.hub.backend.modules.agent.repository.AgentModelRepository;
import com.hub.backend.modules.agent.repository.AgentQuestionRepository;
import com.hub.backend.modules.agent.repository.AgentRepository;
import com.hub.backend.modules.agent.repository.AgentTagRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.ModelFallbackCatalogService;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue, ownership, favourites and independent persistence for the agent module.
 *
 * <p>Agent chats deliberately do not call the generic {@code ConversationService}. Their history
 * has different lifecycle and visibility rules, and mixing the two stores would make deleting an
 * agent thread capable of touching ordinary chat rows.</p>
 */
@Service
@Transactional(readOnly = true)
public class AgentService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 一个智能体只能绑一个对话模型；多模型会让前台冒出切换入口，而站内没有计费闸门兜底。 */
    private static final int MAX_MODELS_PER_AGENT = 1;
    private static final String TITLE_ELLIPSIS = "...";
    private static final Set<String> VISIBILITIES =
            Set.of(AgentEntity.VISIBILITY_PRIVATE, AgentEntity.VISIBILITY_PUBLIC);
    private static final Set<String> PUBLISH_STATUSES =
            Set.of(AgentEntity.PUBLISH_DRAFT, AgentEntity.PUBLISH_PUBLISHED,
                    AgentEntity.PUBLISH_DELETED);
    private static final Set<String> AGENT_STATUSES =
            Set.of(AgentEntity.STATUS_ACTIVE, AgentEntity.STATUS_UNPUBLISHED,
                    AgentEntity.STATUS_DELETED);
    private static final Set<String> AGENT_TYPES =
            Set.of(AgentEntity.TYPE_OFFICIAL, AgentEntity.TYPE_CUSTOM);

    private final AgentRepository agents;
    private final AgentCategoryRepository categories;
    private final AgentTagRepository tags;
    private final AgentAbilityRepository abilities;
    private final AgentModelRepository agentModels;
    private final AgentQuestionRepository questions;
    private final AgentFavoriteRepository favorites;
    private final AgentConversationRepository conversations;
    private final AgentChatMessageRepository messages;
    private final LlmModelRepository models;
    private final ModelFallbackCatalogService fallbackCatalog;
    private final CommerceToolRepository commerceTools;
    private final UserAccountRepository userAccounts;
    private final ChatProperties chatProperties;
    private final AgentJsonColumnCodec jsonColumns;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository agents,
                        AgentCategoryRepository categories,
                        AgentTagRepository tags,
                        AgentAbilityRepository abilities,
                        AgentModelRepository agentModels,
                        AgentQuestionRepository questions,
                        AgentFavoriteRepository favorites,
                        AgentConversationRepository conversations,
                        AgentChatMessageRepository messages,
                        LlmModelRepository models,
                        ModelFallbackCatalogService fallbackCatalog,
                        CommerceToolRepository commerceTools,
                        UserAccountRepository userAccounts,
                        ChatProperties chatProperties,
                        AgentJsonColumnCodec jsonColumns,
                        ObjectMapper objectMapper) {
        this.agents = agents;
        this.categories = categories;
        this.tags = tags;
        this.abilities = abilities;
        this.agentModels = agentModels;
        this.questions = questions;
        this.favorites = favorites;
        this.conversations = conversations;
        this.messages = messages;
        this.models = models;
        this.fallbackCatalog = fallbackCatalog;
        this.commerceTools = commerceTools;
        this.userAccounts = userAccounts;
        this.chatProperties = chatProperties;
        this.jsonColumns = jsonColumns;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ catalogue

    public List<AgentCategoryResponse> listCategories(Long siteId) {
        return listAdminCategories(siteId);
    }

    public List<AgentCategoryResponse> listAdminCategories(Long siteId) {
        List<AgentCategoryEntity> result = siteId == null
                ? categories.findByStatusOrderBySiteIdAscSortOrderAscIdAsc(
                        AgentCategoryEntity.STATUS_ACTIVE)
                : categories.findBySiteIdAndStatusOrderBySortOrderAscIdAsc(
                        siteId, AgentCategoryEntity.STATUS_ACTIVE);
        return result
                .stream()
                .map(AgentCategoryResponse::from)
                .toList();
    }

    public PageResponse<AgentResponse> list(CurrentUser currentUser,
                                            Integer page,
                                            Integer size,
                                            Long categoryId,
                                            Long excludeCategoryId,
                                            String childClassify,
                                            String keyword,
                                            boolean mine,
                                            boolean favorite,
                                            Boolean hot,
                                            Integer scope,
                                            String sort) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = normalizeSize(size);

        List<Long> favouriteIds = null;
        if (favorite) {
            favouriteIds = favorites.findByUserIdOrderByCreatedAtDescIdDesc(currentUser.userId())
                    .stream()
                    .map(AgentFavoriteEntity::getAgentId)
                    .distinct()
                    .toList();
            if (favouriteIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, resolvedPage, resolvedSize);
            }
        }

        Specification<AgentEntity> specification = catalogueSpecification(
                currentUser.siteId(), currentUser.userId(), categoryId, excludeCategoryId, childClassify, keyword,
                mine, hot, scope,
                favouriteIds);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize, catalogueSort(sort));
        Page<AgentEntity> result = agents.findAll(specification, pageable);

        return PageResponse.of(
                toResponses(result.getContent(), currentUser.userId()),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    /** Public catalogue path used by the storefront before login. */
    public PageResponse<AgentResponse> listPublic(Long siteId,
                                                   Integer page,
                                                   Integer size,
                                                   Long categoryId,
                                                   Long excludeCategoryId,
                                                   String childClassify,
                                                   String keyword,
                                                   Boolean hot,
                                                   Integer scope,
                                                   String sort) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = normalizeSize(size);
        Specification<AgentEntity> specification = catalogueSpecification(
                siteId, null, categoryId, excludeCategoryId, childClassify, keyword, false, hot, scope, null);
        Page<AgentEntity> result = agents.findAll(
                specification, PageRequest.of(resolvedPage, resolvedSize, catalogueSort(sort)));
        return PageResponse.of(
                toResponses(result.getContent(), null),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    @Transactional
    public AgentResponse detail(String reference, CurrentUser currentUser) {
        AgentEntity agent = requireVisible(reference, currentUser);
        agents.incrementSeeCount(agent.getId());
        AgentEntity refreshed = agents.findById(agent.getId())
                .orElseThrow(() -> notFound(agent.getId()));
        return toResponse(refreshed, currentUser.userId());
    }

    @Transactional
    public AgentResponse publicDetail(String reference, Long siteId) {
        AgentEntity agent = findByIdentifier(reference);
        if (!isPublicVisible(agent, siteId)) {
            throw notFound(agent.getId());
        }
        agents.incrementSeeCount(agent.getId());
        AgentEntity refreshed = agents.findById(agent.getId())
                .orElseThrow(() -> notFound(agent.getId()));
        return toResponse(refreshed, null);
    }

    @Transactional
    public AgentResponse create(AgentUserRequest request, CurrentUser currentUser) {
        String name = requireText(request.name(), "name");
        String prompt = requireText(request.systemPrompt(), "systemPrompt");

        String agentKey = "agent-" + compactUuid();
        String sn = "a" + compactUuid();
        AgentEntity agent = new AgentEntity(
                currentUser.siteId(), currentUser.userId(), agentKey, sn, name);
        agent.setAgentType(AgentEntity.TYPE_CUSTOM);
        agent.setSystemPrompt(prompt);
        AgentUpsertRequest changes = request.toUpsertRequest();
        applyChanges(agent, changes, true);
        applyUserLinkedTool(agent, request.linkedToolKey(), currentUser.siteId());
        agent = agents.save(agent);
        replaceRelations(agent, changes, currentUser.siteId());
        return toResponse(agent, currentUser.userId());
    }

    @Transactional
    public AgentResponse update(Long id, AgentUserRequest request, CurrentUser currentUser) {
        AgentEntity agent = requireOwned(id, currentUser);
        if (request.name() != null) {
            requireText(request.name(), "name");
        }
        if (request.systemPrompt() != null) {
            requireText(request.systemPrompt(), "systemPrompt");
        }
        AgentUpsertRequest changes = request.toUpsertRequest();
        applyChanges(agent, changes, false);
        applyUserLinkedTool(agent, request.linkedToolKey(), currentUser.siteId());
        replaceRelations(agent, changes, currentUser.siteId());
        return toResponse(agent, currentUser.userId());
    }

    @Transactional
    public void delete(Long id, CurrentUser currentUser) {
        requireOwned(id, currentUser).softDelete();
    }

    // ------------------------------------------------------------------ administration

    public PageResponse<AgentResponse> listAdmin(Integer page,
                                                 Integer size,
                                                 Long siteId,
                                                 Long categoryId,
                                                 Long ownerUserId,
                                                 String agentType,
                                                 String status,
                                                 String visibility,
                                                 String publishStatus,
                                                 String keyword,
                                                 Boolean hot,
                                                 String sort) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = normalizeSize(size);

        Specification<AgentEntity> specification = adminSpecification(
                siteId, categoryId, ownerUserId, agentType, status, visibility, publishStatus,
                keyword, hot);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize, adminSort(sort));
        Page<AgentEntity> result = agents.findAll(specification, pageable);

        return PageResponse.of(
                toResponses(result.getContent(), null),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    public AgentResponse detailAdmin(Long id) {
        return toResponse(requireAny(id), null);
    }

    @Transactional
    public AgentResponse createAdmin(AgentAdminRequest request) {
        String name = requireText(request.name(), "name");
        String prompt = requireText(request.systemPrompt(), "systemPrompt");
        requireOwnerUser(request.ownerUserId());

        // 后台复制教育智能体时通常只带 categoryId；从分类推导站点，避免因为
        // request.siteId 为空而把同站点分类误判为“分类不存在”。
        Long siteId = request.siteId();
        if (siteId == null && request.categoryId() != null) {
            siteId = categories.findById(request.categoryId())
                    .map(AgentCategoryEntity::getSiteId)
                    .orElse(null);
        }

        AgentEntity agent = new AgentEntity(
                siteId,
                request.ownerUserId(),
                "agent-" + compactUuid(),
                "a" + compactUuid(),
                name);
        agent.setSystemPrompt(prompt);

        AgentUpsertRequest upsertRequest = request.toUpsertRequest();
        applyChanges(agent, upsertRequest, true);
        applyAdminChanges(agent, request, true);
        agent = agents.save(agent);
        replaceRelations(agent, upsertRequest, agent.getSiteId());
        return toResponse(agent, null);
    }

    @Transactional
    public AgentResponse updateAdmin(Long id, AgentAdminRequest request) {
        AgentEntity agent = requireAny(id);
        requireOwnerUser(request.ownerUserId());
        if (request.name() != null) {
            requireText(request.name(), "name");
        }
        if (request.systemPrompt() != null) {
            requireText(request.systemPrompt(), "systemPrompt");
        }

        AgentUpsertRequest upsertRequest = request.toUpsertRequest();
        applyChanges(agent, upsertRequest, false);
        applyAdminChanges(agent, request, false);
        agent = agents.save(agent);
        replaceRelations(agent, upsertRequest, agent.getSiteId());
        return toResponse(agent, null);
    }

    /** 管理端下架只隐藏智能体，不改发布状态，便于按原状态重新上架。 */
    @Transactional
    public AgentResponse unpublishAdmin(Long id) {
        AgentEntity agent = requireAny(id);
        if (AgentEntity.STATUS_DELETED.equals(agent.getStatus())) {
            throw new InvalidRequestException("已删除的智能体不能执行临时下架");
        }
        agent.unpublish();
        return toResponse(agent, null);
    }

    @Transactional
    public void deleteAdmin(Long id) {
        requireAny(id).softDelete();
    }

    @Transactional
    public AgentFavoriteResponse addFavorite(Long id, CurrentUser currentUser) {
        AgentEntity agent = requireVisibleById(id, currentUser);
        Optional<AgentFavoriteEntity> existing =
                favorites.findByUserIdAndAgentId(currentUser.userId(), id);
        if (existing.isEmpty()) {
            favorites.save(new AgentFavoriteEntity(currentUser.userId(), id));
            agents.incrementFavoriteCount(id);
        }
        AgentEntity refreshed = agents.findById(id).orElseThrow(() -> notFound(id));
        return new AgentFavoriteResponse(id, true, refreshed.getFavoriteCount());
    }

    @Transactional
    public AgentFavoriteResponse removeFavorite(Long id, CurrentUser currentUser) {
        AgentEntity agent = requireVisibleById(id, currentUser);
        long deleted = favorites.deleteByUserIdAndAgentId(currentUser.userId(), id);
        if (deleted > 0) {
            agents.decrementFavoriteCount(id);
        }
        AgentEntity refreshed = agents.findById(id).orElseThrow(() -> notFound(id));
        return new AgentFavoriteResponse(id, false, refreshed.getFavoriteCount());
    }

    public AgentEntity requireVisible(String reference, CurrentUser currentUser) {
        AgentEntity agent = findByIdentifier(reference);
        if (!isVisibleTo(agent, currentUser.userId())) {
            throw notFound(agent.getId());
        }
        return agent;
    }

    public AgentEntity requireVisibleBySn(String sn, CurrentUser currentUser) {
        AgentEntity agent = agents.findFirstBySnAndStatus(sn, AgentEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("Agent '%s' was not found".formatted(sn)));
        if (!isVisibleTo(agent, currentUser.userId())) {
            throw new NotFoundException("Agent '%s' was not found".formatted(sn));
        }
        return agent;
    }

    /**
     * Ability keys configured on an agent, in display order.
     *
     * <p>Used by the chat orchestration to decide which tools the model may call — the same
     * labels the console shows now actually gate execution.</p>
     */
    public List<String> abilityKeysOf(Long agentId) {
        return abilities.findByAgentIdOrderBySortOrderAscIdAsc(agentId)
                .stream()
                .map(AgentAbilityEntity::getAbilityKey)
                .toList();
    }

    @Transactional
    public void registerUse(Long agentId) {
        agents.incrementUseCount(agentId);
    }

    // ------------------------------------------------------------------ independent chat persistence

    @Transactional
    public AgentConversationEntity openConversation(Long conversationId,
                                                    Long userId,
                                                    Long agentId,
                                                    Long modelId,
                                                    String title) {
        if (conversationId != null) {
            AgentConversationEntity conversation = conversations.findByIdAndUserIdAndAgentIdAndStatus(
                            conversationId, userId, agentId, AgentConversationEntity.STATUS_ACTIVE)
                    .orElseThrow(() -> conversationNotFound(conversationId));
            if (modelId != null) {
                conversation.setModelId(modelId);
            }
            return conversation;
        }
        return conversations.save(new AgentConversationEntity(
                null, userId, agentId, modelId, title));
    }

    @Transactional
    public AgentChatMessageEntity appendUserMessage(Long conversationId, String content) {
        return appendUserMessage(conversationId, content, List.of());
    }

    /**
     * 某条消息携带的图片引用，解析失败按「没有附件」处理。
     *
     * <p>重建上下文时需要用它在配额内回读图片。</p>
     */
    public List<ChatAttachmentRef> attachmentsOf(AgentChatMessageEntity row) {
        return jsonColumns.decode(row.getAttachmentsJson(), ChatAttachmentRef.class);
    }

    /** 用户消息携带图片时，一并落库引用（二进制在 uploaded_files，这里只存指针） */
    @Transactional
    public AgentChatMessageEntity appendUserMessage(Long conversationId,
                                                    String content,
                                                    List<ChatAttachmentRef> attachments) {
        return append(conversationId, null, AgentChatMessageEntity.ROLE_USER, content,
                null, null, jsonColumns.encode(attachments),
                AgentChatMessageEntity.STATUS_COMPLETED, null, null, null, null, null, null);
    }

    @Transactional
    public AgentChatMessageEntity appendAssistantMessage(Long conversationId,
                                                         Long modelId,
                                                         String content,
                                                         String thinkingContent,
                                                         LlmUsage usage,
                                                         String providerMessageId) {
        return appendAssistantMessage(conversationId, modelId, content, thinkingContent, usage,
                providerMessageId, List.of());
    }

    @Transactional
    public AgentChatMessageEntity appendAssistantMessage(Long conversationId,
                                                         Long modelId,
                                                         String content,
                                                         String thinkingContent,
                                                         LlmUsage usage,
                                                         String providerMessageId,
                                                         List<AgentToolCallRecord> executedTools) {
        return appendAssistantMessage(conversationId, modelId, content, thinkingContent, usage,
                providerMessageId, executedTools, null, null);
    }

    /**
     * 重新生成时用：答案进原答案的版本组，并记下它回答的用户消息。
     *
     * <p>两者都为 {@code null} 是普通新的一轮，由 {@link #append} 自己开组。</p>
     */
    @Transactional
    public AgentChatMessageEntity appendAssistantMessage(Long conversationId,
                                                         Long modelId,
                                                         String content,
                                                         String thinkingContent,
                                                         LlmUsage usage,
                                                         String providerMessageId,
                                                         List<AgentToolCallRecord> executedTools,
                                                         Long turnGroupId,
                                                         Long replyToMessageId) {
        return append(conversationId, modelId, AgentChatMessageEntity.ROLE_ASSISTANT, content,
                thinkingContent, jsonColumns.encode(executedTools), null,
                AgentChatMessageEntity.STATUS_COMPLETED, usage,
                providerMessageId, null, null, turnGroupId, replyToMessageId);
    }

    @Transactional
    public AgentChatMessageEntity appendAssistantFailure(Long conversationId,
                                                         Long modelId,
                                                         String errorMessage) {
        return appendAssistantFailure(conversationId, modelId, errorMessage, null, null);
    }

    /**
     * 重新生成失败时也要落进原版本组，否则库里会多出一条不属于任何组的答案，
     * 时间线上看起来像凭空冒出的第二次回答。
     */
    @Transactional
    public AgentChatMessageEntity appendAssistantFailure(Long conversationId,
                                                         Long modelId,
                                                         String errorMessage,
                                                         Long turnGroupId,
                                                         Long replyToMessageId) {
        return append(conversationId, modelId, AgentChatMessageEntity.ROLE_ASSISTANT, "",
                null, null, null, AgentChatMessageEntity.STATUS_FAILED, null, null,
                "LLM_INVOCATION_FAILED", truncate(errorMessage, 1000), turnGroupId, replyToMessageId);
    }

    @Transactional
    public AgentChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                           Long modelId,
                                                           String partialContent,
                                                           String thinkingContent,
                                                           LlmUsage usage) {
        return appendAssistantCancelled(conversationId, modelId, partialContent, thinkingContent,
                usage, List.of());
    }

    /**
     * 用户中途停止时也要保留已经跑完的工具调用 —— 图可能已经生成出来了，
     * 只是总结那段话没来得及说完，把卡片丢掉反而让人以为白等了。
     */
    @Transactional
    public AgentChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                           Long modelId,
                                                           String partialContent,
                                                           String thinkingContent,
                                                           LlmUsage usage,
                                                           List<AgentToolCallRecord> executedTools) {
        return appendAssistantCancelled(conversationId, modelId, partialContent, thinkingContent,
                usage, executedTools, null, null);
    }

    /** 同上：重新生成被用户中途停下时，已收到的半句要留在原版本组里。 */
    @Transactional
    public AgentChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                           Long modelId,
                                                           String partialContent,
                                                           String thinkingContent,
                                                           LlmUsage usage,
                                                           List<AgentToolCallRecord> executedTools,
                                                           Long turnGroupId,
                                                           Long replyToMessageId) {
        return append(conversationId, modelId, AgentChatMessageEntity.ROLE_ASSISTANT,
                partialContent == null ? "" : partialContent, thinkingContent,
                jsonColumns.encode(executedTools), null,
                AgentChatMessageEntity.STATUS_CANCELLED, usage, null, null, null,
                turnGroupId, replyToMessageId);
    }

    // ---------------------------------------------------------------- 版本（编辑 / 重新生成）

    /** 重新生成的准备结果：新答案要加入的组，以及它回答的用户消息。 */
    public record RegeneratePrep(Long turnGroupId, Long replyToMessageId) {
    }

    /**
     * 编辑用户消息：把该条及之后的活跃消息停用，返回新版本应加入的组。
     *
     * <p>停用而不是删除 —— 旧问题和旧答案留在库里供 {@code 1/N} 切换。后面的轮次一并停用，
     * 因为用户改的是更早的问题，原来那些追问都建立在旧问题上。</p>
     */
    @Transactional
    public Long prepareEdit(Long conversationId, Long userId, Long userMessageId) {
        AgentConversationEntity conversation = requireConversation(conversationId, userId);
        AgentChatMessageEntity target = requireMessage(conversation.getId(), userMessageId);
        if (!AgentChatMessageEntity.ROLE_USER.equalsIgnoreCase(target.getRole())) {
            throw new InvalidRequestException(ApiMessages.CHAT_EDIT_TARGET);
        }
        Long turnGroupId = resolveTurnGroupId(target);
        deactivateFromSequence(conversation.getId(), target.getSequenceNo());
        return turnGroupId;
    }

    /**
     * 重新生成：不动用户消息，新答案进原答案的版本组。
     *
     * <p>{@code replyToMessageId} 沿用原答案记下的用户消息；V64 之前的老行没有这个字段，
     * 退化成「往前找最近一条 user」。</p>
     */
    @Transactional
    public RegeneratePrep prepareRegenerate(Long conversationId, Long userId, Long assistantMessageId) {
        AgentConversationEntity conversation = requireConversation(conversationId, userId);
        AgentChatMessageEntity target = requireMessage(conversation.getId(), assistantMessageId);
        if (!AgentChatMessageEntity.ROLE_ASSISTANT.equalsIgnoreCase(target.getRole())) {
            throw new InvalidRequestException(ApiMessages.CHAT_REGENERATE_TARGET);
        }
        Long turnGroupId = resolveTurnGroupId(target);
        Long replyTo = target.getReplyToMessageId() != null
                ? target.getReplyToMessageId()
                : findPrecedingUserMessageId(conversation.getId(), target.getSequenceNo());
        deactivateFromSequence(conversation.getId(), target.getSequenceNo());
        return new RegeneratePrep(turnGroupId, replyTo);
    }

    /** 编辑后写入新版本的 user 消息（进指定版本组）。 */
    @Transactional
    public AgentChatMessageEntity appendUserMessageVersion(Long conversationId,
                                                           Long turnGroupId,
                                                           String content,
                                                           List<ChatAttachmentRef> attachments) {
        return append(conversationId, null, AgentChatMessageEntity.ROLE_USER, content,
                null, null, jsonColumns.encode(attachments),
                AgentChatMessageEntity.STATUS_COMPLETED, null, null, null, null,
                turnGroupId, null);
    }

    /**
     * 把某个版本设为该组的活跃版本，同组兄弟停用。
     *
     * <p>切到用户消息的某个编辑版本时顺带把配对答案换过去：先把指向该组任一版本的答案全部
     * 停用，再激活这个版本自己的答案。该版本还没重新生成过时下面没有答案，时间线就停在
     * 「问题已改、等你重新发送」—— 这是有意的，拿另一版的答案充数更糟。</p>
     *
     * <p><b>边界</b>：不恢复编辑时被截断的后续轮次。要继续后面的对话，用户接着聊即可；
     * 完整分支树不在这一版范围内（与 chat 侧的取舍一致）。</p>
     */
    @Transactional
    public AgentChatMessageResponse activateMessage(Long conversationId, Long userId, Long messageId) {
        AgentConversationEntity conversation = requireConversation(conversationId, userId);
        AgentChatMessageEntity target = requireMessage(conversation.getId(), messageId);
        Long turnGroupId = resolveTurnGroupId(target);

        List<AgentChatMessageEntity> siblings = messages
                .findByAgentConversationIdAndTurnGroupIdOrderByVersionAsc(
                        conversation.getId(), turnGroupId);
        for (AgentChatMessageEntity sibling : siblings) {
            if (Objects.equals(sibling.getId(), target.getId())) {
                sibling.activate();
            } else {
                sibling.deactivate();
            }
        }
        messages.saveAll(siblings);

        if (AgentChatMessageEntity.ROLE_USER.equalsIgnoreCase(target.getRole())) {
            switchAnswerForUserVersion(conversation.getId(), siblings, target);
        }
        return AgentChatMessageResponse.from(
                target,
                jsonColumns.decode(target.getToolCallsJson(), AgentToolCallRecord.class),
                jsonColumns.decode(target.getAttachmentsJson(), ChatAttachmentRef.class),
                siblings.size());
    }

    /** 会话内每个版本组各有多少个版本。 */
    private Map<Long, Integer> versionCounts(Long conversationId) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : messages.countVersionsByConversation(conversationId)) {
            if (row == null || row.length < 2 || !(row[0] instanceof Long groupId)) {
                continue;
            }
            counts.put(groupId, ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** 该 user 版本组各版本的答案先全部停用，再激活目标版本自己的答案。 */
    private void switchAnswerForUserVersion(Long conversationId,
                                            List<AgentChatMessageEntity> userVersions,
                                            AgentChatMessageEntity target) {
        List<Long> versionIds = userVersions.stream().map(AgentChatMessageEntity::getId).toList();
        List<AgentChatMessageEntity> answers = messages
                .findByAgentConversationIdAndReplyToMessageIdInOrderBySequenceNoAsc(
                        conversationId, versionIds);
        if (answers.isEmpty()) {
            return;
        }
        AgentChatMessageEntity chosen = null;
        for (AgentChatMessageEntity answer : answers) {
            if (Objects.equals(answer.getReplyToMessageId(), target.getId())) {
                chosen = answer;
            }
        }
        for (AgentChatMessageEntity answer : answers) {
            if (answer == chosen) {
                answer.activate();
            } else {
                answer.deactivate();
            }
        }
        messages.saveAll(answers);
    }

    private void deactivateFromSequence(Long conversationId, int sequenceNo) {
        List<AgentChatMessageEntity> tail = messages
                .findByAgentConversationIdAndActiveTrueAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(
                        conversationId, sequenceNo);
        if (tail.isEmpty()) {
            return;
        }
        tail.forEach(AgentChatMessageEntity::deactivate);
        messages.saveAll(tail);
    }

    private AgentChatMessageEntity requireMessage(Long conversationId, Long messageId) {
        return messages.findById(messageId)
                .filter(row -> Objects.equals(row.getAgentConversationId(), conversationId))
                .orElseThrow(() -> new NotFoundException("消息 %d 不存在".formatted(messageId)));
    }

    private Long resolveTurnGroupId(AgentChatMessageEntity row) {
        return row.getTurnGroupId() != null ? row.getTurnGroupId() : row.getId();
    }

    private Long findPrecedingUserMessageId(Long conversationId, int beforeSequence) {
        Long found = null;
        for (AgentChatMessageEntity row
                : messages.findByAgentConversationIdOrderBySequenceNoAsc(conversationId)) {
            if (row.getSequenceNo() >= beforeSequence) {
                break;
            }
            if (AgentChatMessageEntity.ROLE_USER.equalsIgnoreCase(row.getRole())) {
                found = row.getId();
            }
        }
        return found;
    }

    public PageResponse<AgentConversationSummaryResponse> listConversations(
            Long userId, Long agentId, Long categoryId, String keyword, Integer page, Integer size) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);

        Page<AgentConversationEntity> result;
        if (agentId != null) {
            result = conversations.findByUserIdAndAgentIdAndStatusOrderByUpdatedAtDescIdDesc(
                    userId, agentId, AgentConversationEntity.STATUS_ACTIVE, pageable);
        } else if (categoryId != null) {
            String searchText = keyword == null || keyword.isBlank()
                    ? null
                    : "%" + keyword.strip().toLowerCase(Locale.ROOT) + "%";
            result = conversations.findByUserIdAndCategoryId(
                    userId,
                    categoryId,
                    AgentConversationEntity.STATUS_ACTIVE,
                    searchText,
                    pageable);
        } else {
            result = conversations.findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
                    userId, AgentConversationEntity.STATUS_ACTIVE, pageable);
        }

        return PageResponse.of(
                result.getContent().stream().map(AgentConversationSummaryResponse::from).toList(),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    public AgentConversationSummaryResponse getConversation(Long conversationId, Long userId) {
        return AgentConversationSummaryResponse.from(requireConversation(conversationId, userId));
    }

    public AgentChatMessageCursorResponse listMessages(Long conversationId,
                                                       Long userId,
                                                       Long cursor,
                                                       Integer size) {
        requireConversation(conversationId, userId);
        int resolvedSize = normalizeSize(size);
        long resolvedCursor = cursor == null || cursor < 0 ? 0L : cursor;
        List<AgentChatMessageEntity> rows =
                messages.findByAgentConversationIdAndIdGreaterThanOrderByIdAsc(
                        conversationId, resolvedCursor, PageRequest.of(0, resolvedSize + 1));

        boolean hasMore = rows.size() > resolvedSize;
        List<AgentChatMessageEntity> pageRows = hasMore ? rows.subList(0, resolvedSize) : rows;
        Map<Long, Integer> versionCounts = versionCounts(conversationId);
        List<AgentChatMessageResponse> items = pageRows.stream()
                .map(row -> AgentChatMessageResponse.from(
                        row,
                        jsonColumns.decode(row.getToolCallsJson(), AgentToolCallRecord.class),
                        jsonColumns.decode(row.getAttachmentsJson(), ChatAttachmentRef.class),
                        versionCounts.getOrDefault(resolveTurnGroupId(row), 1)))
                .toList();
        if (!hasMore) {
            return AgentChatMessageCursorResponse.terminal(items);
        }
        return AgentChatMessageCursorResponse.more(items, pageRows.get(pageRows.size() - 1).getId());
    }

    @Transactional
    public void deleteConversation(Long conversationId, Long userId) {
        requireConversation(conversationId, userId).softDelete(LocalDateTime.now());
    }

    // ------------------------------------------------------------------ internals

    private Specification<AgentEntity> catalogueSpecification(Long siteId,
                                                              Long userId,
                                                              Long categoryId,
                                                              Long excludeCategoryId,
                                                              String childClassify,
                                                              String keyword,
                                                              boolean mine,
                                                              Boolean hot,
                                                              Integer scope,
                                                              List<Long> favouriteIds) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("status"), AgentEntity.STATUS_ACTIVE));
            if (siteId != null) {
                predicates.add(builder.equal(root.get("siteId"), siteId));
            }

            if (mine) {
                predicates.add(builder.equal(root.get("ownerUserId"), userId));
            } else {
                Predicate publicAgent = builder.and(
                        builder.equal(root.get("visibility"), AgentEntity.VISIBILITY_PUBLIC),
                        builder.equal(root.get("publishStatus"), AgentEntity.PUBLISH_PUBLISHED),
                        builder.equal(root.get("scope"), AgentEntity.SCOPE_PUBLIC));
                // The default catalogue is the public catalogue. Keep a user's private agents
                // out of "official" results; when explicitly listing favourites, however,
                // retain private agents owned by the current user (they are visible to them).
                if (favouriteIds != null) {
                    predicates.add(builder.or(
                            publicAgent, builder.equal(root.get("ownerUserId"), userId)));
                } else {
                    predicates.add(publicAgent);
                }
            }
            if (categoryId != null) {
                predicates.add(builder.equal(root.get("categoryId"), categoryId));
            }
            if (excludeCategoryId != null && !excludeCategoryId.equals(categoryId)) {
                predicates.add(builder.or(
                        builder.isNull(root.get("categoryId")),
                        builder.notEqual(root.get("categoryId"), excludeCategoryId)));
            }
            if (childClassify != null && !childClassify.isBlank()) {
                predicates.add(builder.equal(root.get("childClassify"), childClassify.strip()));
            }
            if (scope != null) {
                predicates.add(builder.equal(root.get("scope"), scope));
            }
            if (Boolean.TRUE.equals(hot)) {
                predicates.add(builder.isTrue(root.get("hot")));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.strip().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern),
                        builder.like(builder.lower(root.get("classify")), pattern),
                        builder.like(builder.lower(root.get("childClassify")), pattern)));
            }
            if (favouriteIds != null) {
                predicates.add(root.get("id").in(favouriteIds));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<AgentEntity> adminSpecification(Long siteId,
                                                                 Long categoryId,
                                                                 Long ownerUserId,
                                                                 String agentType,
                                                                 String status,
                                                                 String visibility,
                                                                 String publishStatus,
                                                                 String keyword,
                                                                 Boolean hot) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (siteId != null) {
                predicates.add(builder.equal(root.get("siteId"), siteId));
            }
            if (categoryId != null) {
                predicates.add(builder.equal(root.get("categoryId"), categoryId));
            }
            if (ownerUserId != null) {
                predicates.add(builder.equal(root.get("ownerUserId"), ownerUserId));
            }
            if (agentType != null && !agentType.isBlank()) {
                predicates.add(builder.equal(
                        root.get("agentType"), normalizeEnum(agentType, AGENT_TYPES, "agentType")));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(builder.equal(
                        root.get("status"), normalizeEnum(status, AGENT_STATUSES, "status")));
            }
            if (visibility != null && !visibility.isBlank()) {
                predicates.add(builder.equal(
                        root.get("visibility"), normalizeEnum(visibility, VISIBILITIES, "visibility")));
            }
            if (publishStatus != null && !publishStatus.isBlank()) {
                predicates.add(builder.equal(
                        root.get("publishStatus"),
                        normalizeEnum(publishStatus, PUBLISH_STATUSES, "publishStatus")));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.strip().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern),
                        builder.like(builder.lower(root.get("agentKey")), pattern),
                        builder.like(builder.lower(root.get("sn")), pattern),
                        builder.like(builder.lower(root.get("classify")), pattern),
                        builder.like(builder.lower(root.get("childClassify")), pattern),
                        builder.like(builder.lower(root.get("sourcePath")), pattern)));
            }
            if (hot != null) {
                predicates.add(hot ? builder.isTrue(root.get("hot")) : builder.isFalse(root.get("hot")));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Sort catalogueSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.desc("score"), Sort.Order.asc("id"));
        }
        return switch (sort.strip().toLowerCase(Locale.ROOT)) {
            case "hot" -> Sort.by(
                    Sort.Order.desc("seeCount"), Sort.Order.desc("useCount"), Sort.Order.desc("score"));
            case "score" -> Sort.by(Sort.Order.desc("score"), Sort.Order.desc("seeCount"));
            case "latest" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            default -> Sort.by(
                    Sort.Order.asc("sortOrder"), Sort.Order.desc("score"), Sort.Order.asc("id"));
        };
    }

    private static Sort adminSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
        }
        return switch (sort.strip().toLowerCase(Locale.ROOT)) {
            case "sort", "sortorder", "sort-order" -> Sort.by(
                    Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
            case "score" -> Sort.by(
                    Sort.Order.desc("score"), Sort.Order.desc("seeCount"), Sort.Order.desc("id"));
            case "hot", "popular" -> Sort.by(
                    Sort.Order.desc("seeCount"), Sort.Order.desc("useCount"),
                    Sort.Order.desc("favoriteCount"), Sort.Order.desc("id"));
            case "created", "latest" -> Sort.by(
                    Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            default -> Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
        };
    }

    private AgentEntity findByIdentifier(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new NotFoundException("Agent was not found");
        }
        String value = reference.strip();
        try {
            long id = Long.parseLong(value);
            return agents.findByIdAndStatus(id, AgentEntity.STATUS_ACTIVE)
                    .orElseThrow(() -> notFound(id));
        } catch (NumberFormatException ignored) {
            return agents.findFirstBySnAndStatus(value, AgentEntity.STATUS_ACTIVE)
                    .orElseThrow(() -> new NotFoundException("Agent '%s' was not found".formatted(value)));
        }
    }

    private AgentEntity requireVisibleById(Long id, CurrentUser currentUser) {
        AgentEntity agent = agents.findByIdAndStatus(id, AgentEntity.STATUS_ACTIVE)
                .orElseThrow(() -> notFound(id));
        if (!isVisibleTo(agent, currentUser.userId())) {
            throw notFound(id);
        }
        return agent;
    }

    private AgentEntity requireOwned(Long id, CurrentUser currentUser) {
        AgentEntity agent = agents.findByIdAndStatus(id, AgentEntity.STATUS_ACTIVE)
                .orElseThrow(() -> notFound(id));
        if (!Objects.equals(agent.getOwnerUserId(), currentUser.userId())) {
            throw notFound(id);
        }
        return agent;
    }

    private AgentEntity requireAny(Long id) {
        return agents.findById(id).orElseThrow(() -> notFound(id));
    }

    private static boolean isVisibleTo(AgentEntity agent, Long userId) {
        if (Objects.equals(agent.getOwnerUserId(), userId)) {
            return true;
        }
        return AgentEntity.VISIBILITY_PUBLIC.equals(agent.getVisibility())
                && AgentEntity.PUBLISH_PUBLISHED.equals(agent.getPublishStatus())
                && Integer.valueOf(AgentEntity.SCOPE_PUBLIC).equals(agent.getScope());
    }

    private static boolean isPublicVisible(AgentEntity agent, Long siteId) {
        return Objects.equals(agent.getSiteId(), siteId)
                && AgentEntity.VISIBILITY_PUBLIC.equals(agent.getVisibility())
                && AgentEntity.PUBLISH_PUBLISHED.equals(agent.getPublishStatus())
                && Integer.valueOf(AgentEntity.SCOPE_PUBLIC).equals(agent.getScope());
    }

    private void applyChanges(AgentEntity agent, AgentUpsertRequest request, boolean create) {
        if (request.name() != null) {
            agent.setName(request.name().strip());
        }
        if (request.description() != null) {
            agent.setDescription(request.description());
        }
        if (request.iconUrl() != null) {
            // Blank clears the icon so admin "清除" becomes a real null in storage.
            String icon = request.iconUrl().strip();
            agent.setIconUrl(icon.isEmpty() ? null : icon);
        }
        if (request.systemPrompt() != null) {
            agent.setSystemPrompt(request.systemPrompt());
        }
        if (request.chatPrologue() != null) {
            agent.setChatPrologue(request.chatPrologue());
        }
        if (request.categoryId() != null) {
            requireCategory(request.categoryId(), agent.getSiteId());
            agent.setCategoryId(request.categoryId());
        }
        if (request.uiType() != null) {
            agent.setUiType(request.uiType());
        }
        if (request.scope() != null) {
            agent.setScope(request.scope());
        }
        if (request.classify() != null) {
            agent.setClassify(request.classify());
        }
        if (request.childClassify() != null) {
            agent.setChildClassify(request.childClassify());
        }
        if (request.chatSuggest() != null) {
            agent.setChatSuggest(request.chatSuggest());
        }
        if (request.uiBg() != null) {
            agent.setUiBg(request.uiBg());
        }
        if (request.score() != null) {
            agent.setScore(request.score().setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (request.visibility() != null) {
            agent.setVisibility(normalizeEnum(
                    request.visibility(), VISIBILITIES, "visibility"));
        }
        if (request.publishStatus() != null) {
            agent.setPublishStatus(normalizeEnum(
                    request.publishStatus(), PUBLISH_STATUSES, "publishStatus"));
        }
        if (AgentEntity.PUBLISH_PUBLISHED.equals(agent.getPublishStatus())
                && agent.getPublishedAt() == null) {
            agent.setPublishedAt(LocalDateTime.now());
        }
        if (create && agent.getScore() == null) {
            agent.setScore(new BigDecimal("5.00"));
        }
    }

    /**
     * User-plane agents may opt into one currently active commerce tool, but cannot point at a
     * deleted or unknown tool. Null keeps the existing value on PATCH; an empty string clears it.
     */
    private void applyUserLinkedTool(AgentEntity agent, String requestedKey, Long siteId) {
        if (requestedKey == null) {
            return;
        }
        String toolKey = requestedKey.strip();
        if (toolKey.isEmpty()) {
            agent.setLinkedToolKey(null);
            return;
        }
        commerceTools.findFirstBySiteIdAndToolKeyAndStatus(
                        siteId, toolKey, CommerceToolEntity.STATUS_ACTIVE)
                .or(() -> commerceTools.findByToolKey(toolKey).stream()
                        .filter(item -> CommerceToolEntity.STATUS_ACTIVE.equalsIgnoreCase(item.getStatus()))
                        .filter(item -> item.getSiteId() == null || Objects.equals(item.getSiteId(), siteId))
                        .findFirst())
                .orElseThrow(() -> new InvalidRequestException(
                        "linkedToolKey must reference an active commerce tool"));
        agent.setLinkedToolKey(toolKey);
    }

    private void applyAdminChanges(AgentEntity agent, AgentAdminRequest request, boolean create) {
        if (request.siteId() != null) {
            agent.setSiteId(request.siteId());
        }
        // 引用电商工具：null = 不动，空串 = 取消引用（清回纯对话形态）
        if (request.linkedToolKey() != null) {
            String toolKey = request.linkedToolKey().strip();
            agent.setLinkedToolKey(toolKey.isEmpty() ? null : toolKey);
        }
        if (request.uiConfig() != null) {
            agent.setUiConfigJson(writeUiConfig(request.uiConfig()));
        }
        if (request.ownerUserId() != null || create) {
            agent.setOwnerUserId(request.ownerUserId());
        }
        if (request.agentType() != null) {
            agent.setAgentType(normalizeEnum(request.agentType(), AGENT_TYPES, "agentType"));
        } else if (create) {
            agent.setAgentType(request.ownerUserId() == null
                    ? AgentEntity.TYPE_OFFICIAL : AgentEntity.TYPE_CUSTOM);
        }
        if (request.sourceModel() != null) {
            agent.setSourceModel(request.sourceModel());
        }
        if (request.sourcePath() != null) {
            agent.setSourcePath(request.sourcePath());
        }
        if (request.status() != null) {
            String status = normalizeEnum(request.status(), AGENT_STATUSES, "status");
            if (AgentEntity.STATUS_DELETED.equals(status)) {
                agent.softDelete();
            } else if (AgentEntity.STATUS_UNPUBLISHED.equals(status)) {
                if (AgentEntity.STATUS_DELETED.equals(agent.getStatus())) {
                    throw new InvalidRequestException("已删除的智能体不能临时下架，请先恢复为草稿");
                }
                agent.unpublish();
            } else {
                agent.setStatus(AgentEntity.STATUS_ACTIVE);
                if (AgentEntity.PUBLISH_DELETED.equals(agent.getPublishStatus())) {
                    agent.setPublishStatus(AgentEntity.PUBLISH_DRAFT);
                }
            }
        }
        if (request.sortOrder() != null) {
            agent.setSortOrder(request.sortOrder());
        }
        if (request.hot() != null) {
            agent.setHot(request.hot());
        }
        if (request.seeCount() != null) {
            agent.setSeeCount(request.seeCount());
        }
        if (request.useCount() != null) {
            agent.setUseCount(request.useCount());
        }
    }

    private void requireOwnerUser(Long ownerUserId) {
        if (ownerUserId != null && !userAccounts.existsById(ownerUserId)) {
            throw new InvalidRequestException("归属用户不存在：" + ownerUserId);
        }
    }

    private void replaceRelations(AgentEntity agent, AgentUpsertRequest request, Long siteId) {
        if (request.tags() != null) {
            tags.deleteByAgentId(agent.getId());
            List<String> normalized = distinctText(request.tags());
            for (int index = 0; index < normalized.size(); index++) {
                tags.save(new AgentTagEntity(agent.getId(), normalized.get(index), index));
            }
        }

        List<Long> requestedModelIds = request.modelIds();
        if (requestedModelIds != null) {
            agentModels.deleteByAgentId(agent.getId());
            List<Long> normalized = requestedModelIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            // 一个智能体只绑一个模型：systemPrompt 与工具集是围着某个模型调过的，换模型等于换脑子；
            // 前台也据此不展示模型下拉，避免用户切到更贵的档位（站内没有余额与限流闸门）。
            // 请求体上的 @Size(max = 1) 已经拦一层，这里再守一次是防内部调用绕过 DTO 校验。
            if (normalized.size() > MAX_MODELS_PER_AGENT) {
                throw new InvalidRequestException(
                        "每个智能体只能绑定一个对话模型，当前传了 %d 个".formatted(normalized.size()));
            }
            for (Long modelId : normalized) {
                requireChatModel(modelId, siteId);
            }
            if (request.defaultModelId() != null && !normalized.contains(request.defaultModelId())) {
                throw new InvalidRequestException("defaultModelId must be included in modelIds");
            }
            Long defaultId = request.defaultModelId() == null
                    ? (normalized.isEmpty() ? null : normalized.get(0))
                    : request.defaultModelId();
            for (int index = 0; index < normalized.size(); index++) {
                agentModels.save(new AgentModelEntity(
                        agent.getId(), normalized.get(index), normalized.get(index).equals(defaultId), index));
            }
        } else if (request.defaultModelId() != null) {
            List<Long> existing = agentModels.findByAgentIdOrderBySortOrderAscIdAsc(agent.getId())
                    .stream()
                    .map(AgentModelEntity::getModelId)
                    .toList();
            if (!existing.contains(request.defaultModelId())) {
                throw new InvalidRequestException("defaultModelId must be one of the configured modelIds");
            }
            requireChatModel(request.defaultModelId(), siteId);
            agentModels.deleteByAgentId(agent.getId());
            for (int index = 0; index < existing.size(); index++) {
                Long modelId = existing.get(index);
                agentModels.save(new AgentModelEntity(
                        agent.getId(), modelId, modelId.equals(request.defaultModelId()), index));
            }
        }

        if (request.abilityKeys() != null) {
            abilities.deleteByAgentId(agent.getId());
            List<String> normalized = distinctText(request.abilityKeys());
            for (int index = 0; index < normalized.size(); index++) {
                abilities.save(new AgentAbilityEntity(agent.getId(), normalized.get(index), null, index));
            }
        }

        if (request.questions() != null) {
            questions.deleteByAgentId(agent.getId());
            List<String> normalized = distinctText(request.questions());
            for (int index = 0; index < normalized.size(); index++) {
                questions.save(new AgentQuestionEntity(
                        agent.getId(), AgentQuestionEntity.TYPE_SUGGEST, normalized.get(index), index));
            }
        }
    }

    private void requireCategory(Long categoryId, Long siteId) {
        AgentCategoryEntity category = categories.findById(categoryId)
                .orElseThrow(() -> new NotFoundException(
                        "Agent category %d was not found".formatted(categoryId)));
        if (category.getSiteId() != null && !Objects.equals(category.getSiteId(), siteId)) {
            throw new NotFoundException("Agent category %d was not found".formatted(categoryId));
        }
    }

    private LlmModelEntity requireChatModel(Long modelId, Long siteId) {
        LlmModelEntity model = models.findById(modelId)
                .orElseThrow(() -> new NotFoundException("Model %d was not found".formatted(modelId)));
        if (model.getSiteId() != null && !Objects.equals(model.getSiteId(), siteId)) {
            throw new NotFoundException("Model %d was not found".formatted(modelId));
        }
        if (!model.isUpstreamAvailable()
                || !LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus())
                || !LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType())) {
            throw new InvalidRequestException(
                    "Model %d is not an active chat model".formatted(modelId));
        }
        return model;
    }

    private AgentConversationEntity requireConversation(Long conversationId, Long userId) {
        return conversations.findByIdAndUserIdAndStatus(
                        conversationId, userId, AgentConversationEntity.STATUS_ACTIVE)
                .orElseThrow(() -> conversationNotFound(conversationId));
    }

    private AgentChatMessageEntity append(Long conversationId,
                                          Long modelId,
                                          String role,
                                          String content,
                                          String thinkingContent,
                                          String toolCallsJson,
                                          String attachmentsJson,
                                          String status,
                                          LlmUsage usage,
                                          String providerMessageId,
                                          String errorCode,
                                          String errorMessage,
                                          Long turnGroupId,
                                          Long replyToMessageId) {
        int sequenceNo = messages.findMaxSequenceNo(conversationId) + 1;
        AgentChatMessageEntity entity = new AgentChatMessageEntity(
                conversationId, modelId, sequenceNo, role, content, thinkingContent,
                toolCallsJson, attachmentsJson,
                status, usage, providerMessageId, errorCode, errorMessage);
        if (AgentChatMessageEntity.ROLE_ASSISTANT.equals(role) && replyToMessageId != null) {
            entity.bindReplyTo(replyToMessageId);
        }
        if (turnGroupId != null) {
            // 新版本落库前先停用同组里仍活跃的兄弟：任意时刻组内只能有一条 active，
            // 否则重建上下文会同时读到两个版本的答案。
            List<AgentChatMessageEntity> siblings = messages
                    .findByAgentConversationIdAndTurnGroupIdOrderByVersionAsc(
                            conversationId, turnGroupId);
            boolean changed = false;
            for (AgentChatMessageEntity sibling : siblings) {
                if (sibling.isActive()) {
                    sibling.deactivate();
                    changed = true;
                }
            }
            if (changed) {
                messages.saveAll(siblings);
            }
            int nextVersion = messages.findMaxVersion(conversationId, turnGroupId) + 1;
            entity.bindVersion(turnGroupId, Math.max(1, nextVersion));
        } else {
            // turn_group_id 是非空的，但自增 id 要等插入后才知道。先用 0 占位，
            // 插入后改写成自身 id（新的一轮，组号 == 消息号）。
            entity.bindVersion(0L, 1);
        }
        AgentChatMessageEntity saved = messages.save(entity);
        if (saved.getTurnGroupId() == null || saved.getTurnGroupId() == 0L) {
            saved.bindAsOwnTurnGroup();
            saved = messages.save(saved);
        }

        conversations.findById(conversationId).ifPresent(conversation -> {
            LocalDateTime now = LocalDateTime.now();
            conversation.registerMessages(1, now);
            if (AgentChatMessageEntity.ROLE_USER.equals(role)) {
                conversation.applyTitleIfBlank(deriveTitle(content));
            }
        });
        return saved;
    }

    private String deriveTitle(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String flattened = content.strip().replaceAll("\\s+", " ");
        int limit = chatProperties.getTitleMaxLength();
        if (flattened.length() <= limit) {
            return flattened;
        }
        return flattened.substring(0, limit) + TITLE_ELLIPSIS;
    }

    private AgentResponse toResponse(AgentEntity agent, Long userId) {
        Long categoryId = agent.getCategoryId();
        String categoryName = categoryId == null ? null
                : categories.findById(categoryId).map(AgentCategoryEntity::getName).orElse(null);
        List<String> tagValues = tags.findByAgentIdOrderBySortOrderAscIdAsc(agent.getId())
                .stream()
                .map(AgentTagEntity::getTag)
                .toList();
        List<AgentModelEntity> modelRows = agentModels.findByAgentIdOrderBySortOrderAscIdAsc(agent.getId());
        List<Long> modelIds = modelRows.stream().map(AgentModelEntity::getModelId).toList();
        Long defaultModelId = modelRows.stream()
                .filter(AgentModelEntity::isDefaultModel)
                .map(AgentModelEntity::getModelId)
                .findFirst()
                .orElse(null);
        List<AgentModelOptionResponse> modelOptions = modelOptionsOf(
                agent.getSiteId(), modelRows, defaultModelId, models.findAllById(modelIds));
        List<String> abilityKeys = abilities.findByAgentIdOrderBySortOrderAscIdAsc(agent.getId())
                .stream()
                .map(AgentAbilityEntity::getAbilityKey)
                .toList();
        List<String> questionValues = questions.findByAgentIdOrderBySortOrderAscIdAsc(agent.getId())
                .stream()
                .map(AgentQuestionEntity::getContent)
                .toList();
        boolean favorite = userId != null
                && favorites.findByUserIdAndAgentId(userId, agent.getId()).isPresent();

        return new AgentResponse(
                agent.getId(),
                agent.getSiteId(),
                categoryId,
                categoryName,
                agent.getOwnerUserId(),
                agent.getAgentKey(),
                agent.getSn(),
                agent.getAgentType(),
                agent.getUiType(),
                agent.getScope(),
                agent.getClassify(),
                agent.getChildClassify(),
                agent.getLinkedToolKey(),
                readUiConfig(agent.getUiConfigJson()),
                agent.isChatSuggest(),
                agent.getUiBg(),
                agent.getName(),
                agent.getDescription(),
                agent.getIconUrl(),
                agent.getSystemPrompt(),
                agent.getChatPrologue(),
                agent.getSourceModel(),
                agent.getSourcePath(),
                agent.getVisibility(),
                agent.getPublishStatus(),
                agent.getStatus(),
                agent.getScore(),
                agent.getSeeCount(),
                agent.getUseCount(),
                agent.getFavoriteCount(),
                agent.isHot(),
                favorite,
                tagValues,
                modelIds,
                defaultModelId,
                modelOptions,
                abilityKeys,
                questionValues,
                agent.getPublishedAt(),
                agent.getSortOrder(),
                agent.getCreatedAt(),
                agent.getUpdatedAt());
    }

    /**
     * 把 {@code agent_models} 的行翻译成前端能直接渲染的选项。
     *
     * <p>模型被删除、或类型不再是对话模型时跳过：前端少一个选项，好过放一个点下去就报
     * 400 的按钮。</p>
     */
    private List<AgentModelOptionResponse> modelOptionsOf(Long siteId,
                                                          List<AgentModelEntity> rows,
                                                          Long defaultModelId,
                                                          Collection<LlmModelEntity> candidates) {
        if (rows == null || rows.isEmpty() || candidates == null) {
            return List.of();
        }
        Map<Long, LlmModelEntity> index = new HashMap<>();
        for (LlmModelEntity model : candidates) {
            index.put(model.getId(), model);
        }
        List<AgentModelOptionResponse> options = new ArrayList<>(rows.size());
        Set<String> seenGroups = new java.util.HashSet<>();
        for (AgentModelEntity row : rows) {
            LlmModelEntity model = index.get(row.getModelId());
            if (model == null || !LlmModelEntity.TYPE_CHAT.equalsIgnoreCase(model.getModelType())) {
                continue;
            }
            ModelFallbackCatalogService.GroupView group = fallbackCatalog == null
                    ? null
                    : fallbackCatalog.visibleGroupForModel(
                            siteId, LlmModelEntity.TYPE_CHAT, model.getId()).orElse(null);
            if (group == null) {
                // 没有回退分组不代表模型不能用：ModelUseResolver.resolveAll 找不到分组时会直接按
                // key 查目录，调用照样成功。这里若也跟着丢弃，运营在后台配了模型、前台 models[]
                // 却恒为空 —— 配置等于不可见（CHAT 分组一条都没建时就是这种状态）。
                // 所以只在「模型本身已不可用」时跳过，而不是「没分组」就跳过。
                boolean usable = LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus())
                        && model.isUpstreamAvailable();
                if (!usable) {
                    continue;
                }
            }
            String publicKey = group == null ? model.getModelKey() : group.group().getGroupKey();
            String publicName = group == null ? model.getModelName() : group.group().getDisplayName();
            if (!seenGroups.add(publicKey)) {
                continue;
            }
            options.add(AgentModelOptionResponse.of(
                    model, publicKey, publicName,
                    Objects.equals(row.getModelId(), defaultModelId)));
        }
        return options;
    }

    private List<AgentResponse> toResponses(List<AgentEntity> rows, Long userId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(AgentEntity::getId).toList();

        Map<Long, String> categoryNames = new HashMap<>();
        categories.findAllById(rows.stream()
                        .map(AgentEntity::getCategoryId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .forEach(category -> categoryNames.put(category.getId(), category.getName()));

        Map<Long, List<String>> tagValues = new LinkedHashMap<>();
        tags.findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(ids)
                .forEach(tag -> tagValues.computeIfAbsent(tag.getAgentId(), ignored -> new ArrayList<>())
                        .add(tag.getTag()));

        Map<Long, List<AgentModelEntity>> modelRows = new LinkedHashMap<>();
        agentModels.findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(ids)
                .forEach(model -> modelRows.computeIfAbsent(model.getAgentId(), ignored -> new ArrayList<>())
                        .add(model));

        Map<Long, List<String>> abilityValues = new LinkedHashMap<>();
        abilities.findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(ids)
                .forEach(ability -> abilityValues
                        .computeIfAbsent(ability.getAgentId(), ignored -> new ArrayList<>())
                        .add(ability.getAbilityKey()));

        Map<Long, List<String>> questionValues = new LinkedHashMap<>();
        questions.findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(ids)
                .forEach(question -> questionValues
                        .computeIfAbsent(question.getAgentId(), ignored -> new ArrayList<>())
                        .add(question.getContent()));

        Set<Long> favouriteIds = userId == null
                ? Set.of()
                : favorites.findByUserIdAndAgentIdIn(userId, ids).stream()
                        .map(AgentFavoriteEntity::getAgentId)
                        .collect(Collectors.toSet());

        // 整批一次查完，避免每个智能体各查一次模型（列表页一屏就有 20 个）
        Map<Long, LlmModelEntity> modelIndex = new LinkedHashMap<>();
        models.findAllById(modelRows.values().stream()
                        .flatMap(List::stream)
                        .map(AgentModelEntity::getModelId)
                        .distinct()
                        .toList())
                .forEach(model -> modelIndex.put(model.getId(), model));

        return rows.stream().map(agent -> {
            List<AgentModelEntity> agentModelRows = modelRows.getOrDefault(agent.getId(), List.of());
            List<Long> modelIds = agentModelRows.stream().map(AgentModelEntity::getModelId).toList();
            Long defaultModelId = agentModelRows.stream()
                    .filter(AgentModelEntity::isDefaultModel)
                    .map(AgentModelEntity::getModelId)
                    .findFirst()
                    .orElse(null);
            return new AgentResponse(
                    agent.getId(),
                    agent.getSiteId(),
                    agent.getCategoryId(),
                    categoryNames.get(agent.getCategoryId()),
                    agent.getOwnerUserId(),
                    agent.getAgentKey(),
                    agent.getSn(),
                    agent.getAgentType(),
                    agent.getUiType(),
                    agent.getScope(),
                    agent.getClassify(),
                    agent.getChildClassify(),
                    agent.getLinkedToolKey(),
                    readUiConfig(agent.getUiConfigJson()),
                    agent.isChatSuggest(),
                    agent.getUiBg(),
                    agent.getName(),
                    agent.getDescription(),
                    agent.getIconUrl(),
                    agent.getSystemPrompt(),
                    agent.getChatPrologue(),
                    agent.getSourceModel(),
                    agent.getSourcePath(),
                    agent.getVisibility(),
                    agent.getPublishStatus(),
                    agent.getStatus(),
                    agent.getScore(),
                    agent.getSeeCount(),
                    agent.getUseCount(),
                    agent.getFavoriteCount(),
                    agent.isHot(),
                    favouriteIds.contains(agent.getId()),
                    tagValues.getOrDefault(agent.getId(), List.of()),
                    modelIds,
                    defaultModelId,
                    modelOptionsOf(agent.getSiteId(), agentModelRows, defaultModelId,
                            modelIndex.values()),
                    abilityValues.getOrDefault(agent.getId(), List.of()),
                    questionValues.getOrDefault(agent.getId(), List.of()),
                    agent.getPublishedAt(),
                    agent.getSortOrder(),
                    agent.getCreatedAt(),
                    agent.getUpdatedAt());
        }).toList();
    }

    private String writeUiConfig(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > 12000) {
                throw new InvalidRequestException("uiConfig 过长（上限 12000 字符）");
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new InvalidRequestException("uiConfig 不是合法 JSON");
        }
    }

    private Map<String, Object> readUiConfig(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static List<String> distinctText(Collection<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private static String normalizeEnum(String value, Set<String> allowed, String field) {
        String normalized = requireText(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new InvalidRequestException(
                    "%s must be one of %s".formatted(field, String.join(", ", allowed)));
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("%s is required".formatted(field));
        }
        return value.strip();
    }

    private static int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("Agent %d was not found".formatted(id));
    }

    private static NotFoundException conversationNotFound(Long id) {
        return new NotFoundException("Agent conversation %d was not found".formatted(id));
    }
}
