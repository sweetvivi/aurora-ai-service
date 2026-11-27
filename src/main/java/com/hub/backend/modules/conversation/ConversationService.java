package com.hub.backend.modules.conversation;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.agent.AgentJsonColumnCodec;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.modules.chat.dto.ChatArtifactContext;
import com.hub.backend.modules.conversation.dto.ChatMessageCursorResponse;
import com.hub.backend.modules.conversation.dto.ChatMessageResponse;
import com.hub.backend.modules.conversation.dto.ConversationBatchDeleteResponse;
import com.hub.backend.modules.conversation.dto.ConversationSummaryResponse;
import com.hub.backend.modules.conversation.entity.ChatMessageEntity;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ChatMessageRepository;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Persistence and queries for conversation threads and their messages.
 *
 * <p>Two rules hold everywhere in this class:</p>
 * <ul>
 *   <li>message appends run in their own short transactions, so a slow upstream call never holds a
 *       database transaction open;</li>
 *   <li>every lookup is scoped by the owning account. A thread id supplied by a client is never
 *       trusted on its own, otherwise one account could read another account's history.</li>
 * </ul>
 */
@Service
public class ConversationService {

    /** Upper bound of a single message page. */
    static final int MAX_PAGE_SIZE = 100;

    /** Default page size when the client does not ask for one. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Thread kinds a client may create explicitly.
     *
     * <p>Anything else is rejected instead of stored: an unknown type would silently disappear from
     * every typed history query, which looks exactly like data loss.</p>
     */
    private static final Set<String> CREATABLE_TYPES = Set.of(
            ConversationEntity.TYPE_CHAT, ConversationEntity.TYPE_PAINTING);

    private static final String ELLIPSIS = "…";

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final LlmModelRepository models;
    private final ChatProperties chatProperties;
    private final AgentJsonColumnCodec jsonColumns;

    public ConversationService(ConversationRepository conversations,
                               ChatMessageRepository messages,
                               LlmModelRepository models,
                               ChatProperties chatProperties,
                               AgentJsonColumnCodec jsonColumns) {
        this.conversations = conversations;
        this.messages = messages;
        this.models = models;
        this.chatProperties = chatProperties;
        this.jsonColumns = jsonColumns;
    }

    // ---------------------------------------------------------------- write path (chat)

    /**
     * Remembers the IMAGE-model preference for Doubao-style chat tools on this thread.
     * Pass {@code null} to clear (fall back to the site default on the next generation).
     */
    @Transactional
    public void updateImageModelPreference(Long conversationId, Long userId, Long imageModelId) {
        ConversationEntity conversation = requireOwned(conversationId, userId);
        conversation.setImageModelId(imageModelId);
        conversations.save(conversation);
    }

    /**
     * Returns the referenced conversation, or creates a new one for the given owner.
     *
     * @throws NotFoundException when {@code conversationId} is given but is unknown or owned by
     *                           another account
     */
    @Transactional
    public ConversationEntity openConversation(Long conversationId, Long userId, Long modelId, String title) {
        if (conversationId != null) {
            return requireOwned(conversationId, userId);
        }
        return conversations.save(new ConversationEntity(userId, modelId, title));
    }

    @Transactional
    public ChatMessageEntity appendUserMessage(Long conversationId, String content) {
        return appendUserMessage(conversationId, content, List.of());
    }

    @Transactional
    public ChatMessageEntity appendUserMessage(Long conversationId, String content,
                                               List<ChatAttachmentRef> attachments) {
        return append(conversationId, null, ChatMessageEntity.ROLE_USER, content,
                jsonColumns.encode(attachments), null,
                ChatMessageEntity.STATUS_COMPLETED, null, null, null, null, null);
    }

    /**
     * Appends a new user version into an existing turn group (edit-and-resend).
     */
    @Transactional
    public ChatMessageEntity appendUserMessageVersion(Long conversationId, Long turnGroupId,
                                                      String content, List<ChatAttachmentRef> attachments) {
        return append(conversationId, null, ChatMessageEntity.ROLE_USER, content,
                jsonColumns.encode(attachments), null,
                ChatMessageEntity.STATUS_COMPLETED, null, null, null, turnGroupId, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantMessage(Long conversationId, Long modelId, String content, LlmUsage usage) {
        return appendAssistantMessage(conversationId, modelId, content, null, usage, List.of(), null, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantMessage(Long conversationId, Long modelId, String content,
                                                    LlmUsage usage, List<ChatAttachmentRef> attachments) {
        return appendAssistantMessage(conversationId, modelId, content, null, usage, attachments, null, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantMessage(Long conversationId, Long modelId, String content,
                                                    String thinkingContent, LlmUsage usage,
                                                    List<ChatAttachmentRef> attachments) {
        return appendAssistantMessage(conversationId, modelId, content, thinkingContent, usage,
                attachments, null, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantMessage(Long conversationId, Long modelId, String content,
                                                    String thinkingContent, LlmUsage usage,
                                                    List<ChatAttachmentRef> attachments,
                                                    Long turnGroupId) {
        return appendAssistantMessage(conversationId, modelId, content, thinkingContent, usage,
                attachments, turnGroupId, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantMessage(Long conversationId, Long modelId, String content,
                                                    String thinkingContent, LlmUsage usage,
                                                    List<ChatAttachmentRef> attachments,
                                                    Long turnGroupId, Long replyToMessageId) {
        return append(conversationId, modelId, ChatMessageEntity.ROLE_ASSISTANT, content,
                jsonColumns.encode(attachments), thinkingContent,
                ChatMessageEntity.STATUS_COMPLETED, usage, null, null, turnGroupId, replyToMessageId);
    }

    @Transactional
    public ChatMessageEntity appendAssistantMessageWithArtifactContext(
                                                    Long conversationId, Long modelId, String content,
                                                    String thinkingContent, LlmUsage usage,
                                                    List<ChatAttachmentRef> attachments,
                                                    Long turnGroupId, Long replyToMessageId,
                                                    List<ChatArtifactContext> artifactContext) {
        ChatMessageEntity saved = append(conversationId, modelId, ChatMessageEntity.ROLE_ASSISTANT, content,
                jsonColumns.encode(attachments), thinkingContent,
                ChatMessageEntity.STATUS_COMPLETED, usage, null, null, turnGroupId, replyToMessageId);
        saved.bindArtifactContext(jsonColumns.encode(artifactContext));
        return messages.save(saved);
    }

    @Transactional(readOnly = true)
    public List<ChatArtifactContext> latestArtifactContext(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        return messages
                .findFirstByConversationIdAndActiveTrueAndRoleAndArtifactContextJsonIsNotNullOrderBySequenceNoDesc(
                        conversationId, ChatMessageEntity.ROLE_ASSISTANT)
                .map(row -> jsonColumns.decode(
                        row.getArtifactContextJson(), ChatArtifactContext.class))
                .orElseGet(List::of);
    }

    @Transactional
    public ChatMessageEntity appendAssistantFailure(Long conversationId, Long modelId, String errorMessage) {
        return appendAssistantFailure(conversationId, modelId, errorMessage, null, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantFailure(Long conversationId, Long modelId, String errorMessage,
                                                    Long turnGroupId) {
        return appendAssistantFailure(conversationId, modelId, errorMessage, turnGroupId, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantFailure(Long conversationId, Long modelId, String errorMessage,
                                                    Long turnGroupId, Long replyToMessageId) {
        String detail = truncate(errorMessage, 1000);
        // Keep a non-empty content so history reopen still shows the failure in the message list.
        String content = detail == null || detail.isBlank() ? "生成失败" : detail;
        return append(conversationId, modelId, ChatMessageEntity.ROLE_ASSISTANT, content, null, null,
                ChatMessageEntity.STATUS_FAILED, null, "LLM_INVOCATION_FAILED", detail, turnGroupId,
                replyToMessageId);
    }

    /**
     * Records a user turn that never reached a successful model reply (client-side or pre-stream failure).
     *
     * <p>Used when the frontend already created an empty conversation, then generation failed before
     * {@code /chat/stream} could persist the exchange — without this, the history row opens to a
     * blank welcome screen because {@code messages} is empty.</p>
     */
    @Transactional
    public ConversationSummaryResponse recordFailedTurn(Long conversationId, Long userId,
                                                        String userContent, String errorMessage) {
        return recordFailedTurn(conversationId, userId, userContent, errorMessage, List.of());
    }

    @Transactional
    public ConversationSummaryResponse recordFailedTurn(Long conversationId, Long userId,
                                                        String userContent, String errorMessage,
                                                        List<ChatAttachmentRef> attachments) {
        requireOwned(conversationId, userId);
        ChatMessageEntity userMessage = appendUserMessage(conversationId, userContent, attachments);
        appendAssistantFailure(conversationId, null, errorMessage, null, userMessage.getId());
        return getConversation(conversationId, userId);
    }

    /**
     * Records a generation that was stopped before the model finished.
     *
     * <p>The partial answer is kept: it is what the user already saw, and dropping it would make the
     * stored conversation disagree with the screen.</p>
     */
    @Transactional
    public ChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                      Long modelId,
                                                      String partialContent,
                                                      LlmUsage usage) {
        return appendAssistantCancelled(conversationId, modelId, partialContent, null, usage);
    }

    @Transactional
    public ChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                      Long modelId,
                                                      String partialContent,
                                                      String thinkingContent,
                                                      LlmUsage usage) {
        return appendAssistantCancelled(conversationId, modelId, partialContent, thinkingContent, usage, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                      Long modelId,
                                                      String partialContent,
                                                      String thinkingContent,
                                                      LlmUsage usage,
                                                      Long turnGroupId) {
        return appendAssistantCancelled(conversationId, modelId, partialContent, thinkingContent,
                usage, turnGroupId, null);
    }

    @Transactional
    public ChatMessageEntity appendAssistantCancelled(Long conversationId,
                                                      Long modelId,
                                                      String partialContent,
                                                      String thinkingContent,
                                                      LlmUsage usage,
                                                      Long turnGroupId,
                                                      Long replyToMessageId) {
        return append(conversationId, modelId, ChatMessageEntity.ROLE_ASSISTANT,
                partialContent == null ? "" : partialContent, null, thinkingContent,
                ChatMessageEntity.STATUS_CANCELLED, usage, null, null, turnGroupId, replyToMessageId);
    }

    /**
     * Soft-deactivates an assistant turn and everything after it, preparing a regenerate.
     *
     * @return turn group for the new version, plus the user message id the answer should reply to
     */
    @Transactional
    public RegeneratePrep prepareRegenerate(Long conversationId, Long userId, Long assistantMessageId) {
        requireOwned(conversationId, userId);
        ChatMessageEntity target = requireMessage(conversationId, assistantMessageId);
        if (!ChatMessageEntity.ROLE_ASSISTANT.equalsIgnoreCase(target.getRole())) {
            throw new InvalidRequestException(ApiMessages.CHAT_REGENERATE_TARGET);
        }
        Long turnGroupId = resolveTurnGroupId(target);
        Long replyTo = target.getReplyToMessageId();
        if (replyTo == null) {
            replyTo = findPrecedingUserMessageId(conversationId, target.getSequenceNo());
        }
        deactivateFromSequence(conversationId, target.getSequenceNo());
        return new RegeneratePrep(turnGroupId, replyTo);
    }

    /** Result of {@link #prepareRegenerate}: group to join and user message the new answer replies to. */
    public record RegeneratePrep(Long turnGroupId, Long replyToMessageId) {
    }

    /**
     * Soft-deactivates a user turn and everything after it, preparing an edit-and-resend.
     *
     * @return the turn group that the new user version should join
     */
    @Transactional
    public Long prepareEdit(Long conversationId, Long userId, Long userMessageId) {
        requireOwned(conversationId, userId);
        ChatMessageEntity target = requireMessage(conversationId, userMessageId);
        if (!ChatMessageEntity.ROLE_USER.equalsIgnoreCase(target.getRole())) {
            throw new InvalidRequestException(ApiMessages.CHAT_EDIT_TARGET);
        }
        Long turnGroupId = resolveTurnGroupId(target);
        deactivateFromSequence(conversationId, target.getSequenceNo());
        return turnGroupId;
    }

    /**
     * Makes {@code messageId} the active version in its turn group; siblings become inactive.
     *
     * <p>For a user edit group, also activates the paired assistant reply and truncates (or
     * restores) messages after that pair so the active timeline stays consistent for the next
     * model call. Full multi-branch trees are still out of scope.</p>
     */
    @Transactional
    public ChatMessageResponse activateMessage(Long conversationId, Long userId, Long messageId) {
        requireOwned(conversationId, userId);
        ChatMessageEntity target = requireMessage(conversationId, messageId);
        Long turnGroupId = resolveTurnGroupId(target);
        List<ChatMessageEntity> siblings = messages
                .findByConversationIdAndTurnGroupIdOrderByVersionAsc(conversationId, turnGroupId);
        for (ChatMessageEntity sibling : siblings) {
            if (Objects.equals(sibling.getId(), target.getId())) {
                sibling.activate();
            } else {
                sibling.deactivate();
            }
        }
        messages.saveAll(siblings);

        if (ChatMessageEntity.ROLE_USER.equalsIgnoreCase(target.getRole())) {
            activateUserEditPair(conversationId, target, siblings);
        }

        return toResponse(target, siblings.size());
    }

    /**
     * Switches the assistant that replies to {@code target} and adjusts the tail after that pair.
     */
    private void activateUserEditPair(Long conversationId,
                                      ChatMessageEntity target,
                                      List<ChatMessageEntity> userSiblings) {
        List<ChatMessageEntity> orderedUsers = userSiblings.stream()
                .sorted(Comparator.comparingInt(ChatMessageEntity::getSequenceNo)
                        .thenComparingLong(ChatMessageEntity::getId))
                .toList();
        int targetIndex = -1;
        for (int i = 0; i < orderedUsers.size(); i++) {
            if (Objects.equals(orderedUsers.get(i).getId(), target.getId())) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return;
        }

        boolean targetIsTip = targetIndex + 1 >= orderedUsers.size();

        Set<Long> otherPairIds = new HashSet<>();
        ChatMessageEntity chosenAssistant = null;
        for (int i = 0; i < orderedUsers.size(); i++) {
            ChatMessageEntity user = orderedUsers.get(i);
            int userNextSeq = i + 1 < orderedUsers.size()
                    ? orderedUsers.get(i + 1).getSequenceNo()
                    : Integer.MAX_VALUE;
            List<ChatMessageEntity> paired = findPairedAssistants(conversationId, user, userNextSeq);
            if (i == targetIndex) {
                chosenAssistant = pickActiveOrLatest(paired);
                activateAssistantGroup(conversationId, paired, chosenAssistant);
            } else if (!paired.isEmpty()) {
                Set<Long> groups = new HashSet<>();
                for (ChatMessageEntity assistant : paired) {
                    groups.add(resolveTurnGroupId(assistant));
                }
                List<ChatMessageEntity> groupRows = messages
                        .findByConversationIdAndTurnGroupIdInOrderByTurnGroupIdAscVersionAsc(
                                conversationId, groups);
                for (ChatMessageEntity row : groupRows) {
                    otherPairIds.add(row.getId());
                    otherPairIds.add(resolveTurnGroupId(row));
                    row.deactivate();
                }
                messages.saveAll(groupRows);
            }
        }

        for (ChatMessageEntity user : orderedUsers) {
            if (!Objects.equals(user.getId(), target.getId())) {
                otherPairIds.add(user.getId());
            }
        }

        int cutSeq = chosenAssistant != null ? chosenAssistant.getSequenceNo() : target.getSequenceNo();
        List<ChatMessageEntity> after = messages
                .findByConversationIdAndSequenceNoGreaterThan(conversationId, cutSeq);
        List<ChatMessageEntity> dirty = new ArrayList<>();
        for (ChatMessageEntity row : after) {
            boolean otherBranch = otherPairIds.contains(row.getId())
                    || (row.getTurnGroupId() != null && otherPairIds.contains(row.getTurnGroupId()));
            // Non-tip: hide everything after the pair (no full branch tree yet).
            // Tip: restore follow-ups that are not other edit-branch pairs.
            boolean shouldShow = targetIsTip && !otherBranch;
            if (shouldShow) {
                if (!row.isActive()) {
                    row.activate();
                    dirty.add(row);
                }
            } else if (row.isActive()) {
                row.deactivate();
                dirty.add(row);
            }
        }
        if (!dirty.isEmpty()) {
            messages.saveAll(dirty);
        }
    }

    private List<ChatMessageEntity> findPairedAssistants(Long conversationId,
                                                        ChatMessageEntity user,
                                                        int beforeSequenceExclusive) {
        List<ChatMessageEntity> byReply = messages
                .findByConversationIdAndReplyToMessageIdOrderBySequenceNoAsc(conversationId, user.getId());
        if (!byReply.isEmpty()) {
            return byReply;
        }
        // Legacy rows without reply_to: first assistant between this user and the next sibling.
        List<ChatMessageEntity> all = messages.findByConversationIdOrderBySequenceNoAsc(conversationId);
        List<ChatMessageEntity> found = new ArrayList<>();
        for (ChatMessageEntity row : all) {
            if (row.getSequenceNo() <= user.getSequenceNo()) {
                continue;
            }
            if (row.getSequenceNo() >= beforeSequenceExclusive) {
                break;
            }
            if (ChatMessageEntity.ROLE_ASSISTANT.equalsIgnoreCase(row.getRole())) {
                found.add(row);
                break;
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        Long group = resolveTurnGroupId(found.get(0));
        return messages.findByConversationIdAndTurnGroupIdOrderByVersionAsc(conversationId, group);
    }

    private static ChatMessageEntity pickActiveOrLatest(List<ChatMessageEntity> paired) {
        if (paired == null || paired.isEmpty()) {
            return null;
        }
        for (ChatMessageEntity row : paired) {
            if (row.isActive()) {
                return row;
            }
        }
        return paired.stream()
                .max(Comparator.comparingInt(ChatMessageEntity::getVersion)
                        .thenComparingLong(ChatMessageEntity::getId))
                .orElse(paired.get(paired.size() - 1));
    }

    private void activateAssistantGroup(Long conversationId,
                                        List<ChatMessageEntity> paired,
                                        ChatMessageEntity chosen) {
        if (paired == null || paired.isEmpty() || chosen == null) {
            return;
        }
        Long group = resolveTurnGroupId(chosen);
        List<ChatMessageEntity> groupRows = messages
                .findByConversationIdAndTurnGroupIdOrderByVersionAsc(conversationId, group);
        for (ChatMessageEntity row : groupRows) {
            if (Objects.equals(row.getId(), chosen.getId())) {
                row.activate();
            } else {
                row.deactivate();
            }
        }
        messages.saveAll(groupRows);
    }

    /** Decodes the attachment refs stored on a message row. */
    public List<ChatAttachmentRef> attachmentsOf(ChatMessageEntity row) {
        return jsonColumns.decode(row.getAttachmentsJson(), ChatAttachmentRef.class);
    }

    // ---------------------------------------------------------------- read path (history)

    /**
     * Conversation history of one account, newest activity first.
     *
     * @param page zero based page index
     * @param size requested page size, clamped to {@value #MAX_PAGE_SIZE}
     */
    public PageResponse<ConversationSummaryResponse> listConversations(Long userId, Integer page, Integer size) {
        return listConversations(userId, page, size, null);
    }

    /**
     * Conversation history of one account, optionally restricted to a single kind of thread.
     *
     * <p>The drawing page stores each generated image as its own thread; a client that wants the
     * chat history asks for {@code CHAT} and never sees those. A blank or unknown type means
     * "no filter" — the admin console still lists everything.</p>
     *
     * @param conversationType {@code CHAT} / {@code PAINTING}, or {@code null} for all
     */
    @Transactional(readOnly = true)
    public PageResponse<ConversationSummaryResponse> listConversations(Long userId, Integer page, Integer size,
                                                                      String conversationType) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);
        String type = normalizeTypeFilter(conversationType);

        Page<ConversationEntity> result = type == null
                ? conversations.findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
                        userId, ConversationEntity.STATUS_ACTIVE, pageable)
                : conversations.findByUserIdAndStatusAndConversationTypeOrderByUpdatedAtDescIdDesc(
                        userId, ConversationEntity.STATUS_ACTIVE, type, pageable);

        List<ConversationEntity> rows = result.getContent();
        java.util.HashSet<Long> modelIds = new java.util.HashSet<>();
        for (ConversationEntity row : rows) {
            if (row.getModelId() != null) {
                modelIds.add(row.getModelId());
            }
            if (row.getImageModelId() != null) {
                modelIds.add(row.getImageModelId());
            }
        }
        Map<Long, LlmModelEntity> modelById = loadModelsById(modelIds);
        return PageResponse.of(
                rows.stream().map(row -> toSummary(row, modelById)).toList(),
                result.getTotalElements(),
                resolvedPage,
                resolvedSize);
    }

    @Transactional(readOnly = true)
    public ConversationSummaryResponse getConversation(Long conversationId, Long userId) {
        return toSummary(requireOwned(conversationId, userId));
    }

    /**
     * Filters a conversation down to the assistant turns that are safe to show without exposing
     * internal failure details.
     */
    public List<ChatMessageEntity> listShareableMessages(Long conversationId) {
        return messages.findByConversationIdAndActiveTrueOrderBySequenceNoAsc(conversationId).stream()
                .filter(message -> !ChatMessageEntity.STATUS_FAILED.equalsIgnoreCase(message.getStatus()))
                .toList();
    }

    /**
     * One page of messages, ordered by {@code sequenceNo} ascending.
     *
     * <p>With neither cursor: the <em>latest</em> page (chat reopen). With {@code before}: older
     * than that id. With {@code cursor}: newer than that id.</p>
     *
     * @param cursor id after which to load newer messages; ignored when {@code before} is set
     * @param before id before which to load older messages
     */
    @Transactional(readOnly = true)
    public ChatMessageCursorResponse listMessages(Long conversationId, Long userId, Long cursor, Integer size) {
        return listMessages(conversationId, userId, cursor, null, size);
    }

    @Transactional(readOnly = true)
    public ChatMessageCursorResponse listMessages(Long conversationId, Long userId, Long cursor,
                                                  Long before, Integer size) {
        requireOwned(conversationId, userId);

        int resolvedSize = normalizeSize(size);
        Pageable probe = PageRequest.of(0, resolvedSize + 1);

        if (before != null && before > 0) {
            List<ChatMessageEntity> descending = messages
                    .findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, before, probe);
            return pageFromDescending(descending, resolvedSize, true);
        }

        if (cursor != null && cursor > 0) {
            List<ChatMessageEntity> ascending = messages
                    .findByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, cursor, probe);
            boolean hasMore = ascending.size() > resolvedSize;
            List<ChatMessageEntity> pageRows = hasMore ? ascending.subList(0, resolvedSize) : ascending;
            List<ChatMessageResponse> items = toResponses(pageRows);
            Long next = hasMore && !pageRows.isEmpty() ? pageRows.get(pageRows.size() - 1).getId() : null;
            Long prev = pageRows.isEmpty() ? null : pageRows.get(0).getId();
            // Older pages always exist when walking forward from a mid-thread cursor, but the
            // client already has them — report hasOlder=false so it does not refetch.
            return ChatMessageCursorResponse.of(items, next, hasMore, prev, false);
        }

        List<ChatMessageEntity> latestDesc = messages
                .findByConversationIdOrderByIdDesc(conversationId, probe);
        return pageFromDescending(latestDesc, resolvedSize, false);
    }

    private ChatMessageCursorResponse pageFromDescending(List<ChatMessageEntity> descending,
                                                         int resolvedSize,
                                                         boolean walkingOlder) {
        boolean hasOlder = descending.size() > resolvedSize;
        List<ChatMessageEntity> pageDesc = hasOlder
                ? descending.subList(0, resolvedSize)
                : descending;
        List<ChatMessageEntity> ascending = new java.util.ArrayList<>(pageDesc);
        java.util.Collections.reverse(ascending);
        List<ChatMessageResponse> items = toResponses(ascending);
        Long prev = ascending.isEmpty() ? null : ascending.get(0).getId();
        Long next = ascending.isEmpty() ? null : ascending.get(ascending.size() - 1).getId();
        // Latest-open and older-walk both sit at the newest edge of this page; only the initial
        // open (not walkingOlder) is guaranteed to be at the conversation end.
        boolean hasMore = false;
        if (walkingOlder && !ascending.isEmpty()) {
            // There may be messages newer than this older page; the client already shows them.
            hasMore = false;
        }
        return ChatMessageCursorResponse.of(items, hasMore ? next : null, hasMore, prev, hasOlder);
    }

    /**
     * Expands each page row with every sibling in its turn group so the client can render
     * {@code 1/N} even when an inactive version falls outside the id window.
     */
    private List<ChatMessageResponse> toResponses(List<ChatMessageEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long conversationId = rows.get(0).getConversationId();
        Set<Long> groups = new HashSet<>();
        for (ChatMessageEntity row : rows) {
            groups.add(resolveTurnGroupId(row));
        }
        List<ChatMessageEntity> expanded = messages
                .findByConversationIdAndTurnGroupIdInOrderByTurnGroupIdAscVersionAsc(conversationId, groups);
        // Merge page rows with siblings so legacy rows (null turn_group_id) are never dropped.
        Map<Long, ChatMessageEntity> unique = new LinkedHashMap<>();
        for (ChatMessageEntity row : rows) {
            unique.putIfAbsent(row.getId(), row);
        }
        for (ChatMessageEntity row : expanded) {
            unique.putIfAbsent(row.getId(), row);
        }
        Map<Long, Integer> versionCounts = new HashMap<>();
        for (ChatMessageEntity row : unique.values()) {
            Long group = resolveTurnGroupId(row);
            versionCounts.merge(group, 1, Integer::sum);
        }
        List<ChatMessageEntity> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator
                .comparingInt(ChatMessageEntity::getSequenceNo)
                .thenComparingInt(ChatMessageEntity::getVersion)
                .thenComparing(ChatMessageEntity::getId));
        unique.clear();
        for (ChatMessageEntity row : ordered) {
            unique.putIfAbsent(row.getId(), row);
        }
        Map<Long, LlmModelEntity> modelById = loadModelsById(unique.values().stream()
                .map(ChatMessageEntity::getModelId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return unique.values().stream()
                .map(row -> toResponse(
                        row,
                        versionCounts.getOrDefault(resolveTurnGroupId(row), 1),
                        modelById))
                .toList();
    }

    // ---------------------------------------------------------------- explicit creation

    /**
     * Creates an empty thread.
     *
     * <p>The chat path creates a conversation on demand, but a client that wants to prepare a thread
     * before the first turn (or that collects several threads first and only then writes) needs the
     * creation to be callable on its own.</p>
     *
     * @param title optional; when absent it is derived from the first user message
     */
    @Transactional
    public ConversationSummaryResponse createConversation(Long userId, String title) {
        return createConversation(userId, title, null);
    }

    /**
     * Creates an empty thread of the given kind.
     *
     * @param conversationType {@code CHAT} / {@code PAINTING}; {@code null} means {@code CHAT}
     * @throws InvalidRequestException when the type is not one clients may create
     */
    @Transactional
    public ConversationSummaryResponse createConversation(Long userId, String title, String conversationType) {
        String type = normalizeTypeFilter(conversationType);
        if (type == null) {
            type = ConversationEntity.TYPE_CHAT;
        }
        if (!CREATABLE_TYPES.contains(type)) {
            throw new InvalidRequestException("conversationType 不支持：" + conversationType);
        }
        return toSummary(conversations.save(new ConversationEntity(userId, null, title, type)));
    }

    /**
     * Normalises a type supplied by a client.
     *
     * @return {@code null} when the value is absent (callers treat that as "no filter")
     */
    private static String normalizeTypeFilter(String conversationType) {
        if (conversationType == null || conversationType.isBlank()) {
            return null;
        }
        return conversationType.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Appends a user message without invoking a model.
     *
     * <p>Used to record a turn the account already produced elsewhere, or to write a message now and
     * generate the answer later. Generating the reply is what {@code POST /chat/stream} is for.</p>
     *
     * @throws NotFoundException when the thread does not exist or belongs to another account
     */
    @Transactional
    public ChatMessageResponse appendUserMessageTo(Long conversationId, Long userId, String content) {
        requireOwned(conversationId, userId);
        ChatMessageEntity saved = appendUserMessage(conversationId, content);
        return toResponse(saved, 1);
    }

    // ---------------------------------------------------------------- mutations

    @Transactional
    public ConversationSummaryResponse renameConversation(Long conversationId, Long userId, String title) {
        ConversationEntity conversation = requireOwned(conversationId, userId);
        conversation.rename(title);
        return toSummary(conversation);
    }

    /**
     * Soft deletes the thread. Messages are kept so the operation stays reversible and auditable.
     */
    @Transactional
    public void deleteConversation(Long conversationId, Long userId) {
        ConversationEntity conversation = requireOwned(conversationId, userId);
        conversation.softDelete(LocalDateTime.now());
    }

    /**
     * Soft deletes several threads in one round trip.
     *
     * <p>Ids the account does not own (or that are unknown, or already deleted) are skipped rather
     * than reported: the count is returned, but not the reason, so the response cannot be used to
     * find out whether somebody else's thread exists.</p>
     */
    @Transactional
    public ConversationBatchDeleteResponse deleteConversations(Long userId, List<Long> ids) {
        List<Long> distinct = ids == null ? List.of()
                : ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return ConversationBatchDeleteResponse.of(0, 0);
        }

        List<ConversationEntity> owned = conversations.findByIdInAndUserIdAndStatus(
                distinct, userId, ConversationEntity.STATUS_ACTIVE);

        LocalDateTime now = LocalDateTime.now();
        owned.forEach(conversation -> conversation.softDelete(now));

        return ConversationBatchDeleteResponse.of(distinct.size(), owned.size());
    }

    /**
     * @throws NotFoundException when the thread does not exist or belongs to another account
     */
    @Transactional(readOnly = true)
    public ConversationEntity requireOwned(Long conversationId, Long userId) {
        return conversations
                .findByIdAndUserIdAndStatus(conversationId, userId, ConversationEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "Conversation %d was not found".formatted(conversationId)));
    }

    // ---------------------------------------------------------------- internals

    private ChatMessageEntity append(Long conversationId, Long modelId, String role, String content,
                                     String attachmentsJson, String thinkingContent, String status,
                                     LlmUsage usage, String errorCode, String errorMessage,
                                     Long turnGroupId, Long replyToMessageId) {
        int sequenceNo = messages.findMaxSequenceNo(conversationId) + 1;
        ChatMessageEntity entity = new ChatMessageEntity(
                conversationId, modelId, sequenceNo, role, content, attachmentsJson, thinkingContent,
                status, usage, errorCode, errorMessage);
        if (ChatMessageEntity.ROLE_ASSISTANT.equals(role) && replyToMessageId != null) {
            entity.bindReplyTo(replyToMessageId);
        }
        if (turnGroupId != null) {
            // Deactivate prior active siblings before inserting the new version.
            List<ChatMessageEntity> siblings = messages
                    .findByConversationIdAndTurnGroupIdOrderByVersionAsc(conversationId, turnGroupId);
            for (ChatMessageEntity sibling : siblings) {
                if (sibling.isActive()) {
                    sibling.deactivate();
                }
            }
            if (!siblings.isEmpty()) {
                messages.saveAll(siblings);
            }
            int nextVersion = messages.findMaxVersion(conversationId, turnGroupId) + 1;
            entity.bindVersion(turnGroupId, Math.max(1, nextVersion));
        } else {
            // Column is NOT NULL; identity is unknown until insert. Use a same-TX sentinel,
            // then rewrite to the generated id below (turn_group_id == id for new turns).
            entity.bindVersion(0L, 1);
        }
        ChatMessageEntity saved = messages.save(entity);
        if (saved.getTurnGroupId() == null || saved.getTurnGroupId() == 0L) {
            saved.bindAsOwnTurnGroup();
            saved = messages.save(saved);
        }

        conversations.findById(conversationId).ifPresent(conversation -> {
            conversation.registerMessages(1);
            if (ChatMessageEntity.ROLE_USER.equals(role)) {
                conversation.applyTitleIfBlank(deriveTitle(content));
            }
        });
        return saved;
    }

    private Long findPrecedingUserMessageId(Long conversationId, int beforeSequence) {
        List<ChatMessageEntity> all = messages.findByConversationIdOrderBySequenceNoAsc(conversationId);
        Long found = null;
        for (ChatMessageEntity row : all) {
            if (row.getSequenceNo() >= beforeSequence) {
                break;
            }
            if (ChatMessageEntity.ROLE_USER.equalsIgnoreCase(row.getRole())) {
                found = row.getId();
            }
        }
        return found;
    }

    private void deactivateFromSequence(Long conversationId, int fromSequenceInclusive) {
        List<ChatMessageEntity> victims = messages
                .findByConversationIdAndSequenceNoGreaterThanEqual(conversationId, fromSequenceInclusive);
        for (ChatMessageEntity victim : victims) {
            if (victim.isActive()) {
                victim.deactivate();
            }
        }
        if (!victims.isEmpty()) {
            messages.saveAll(victims);
        }
    }

    private ChatMessageEntity requireMessage(Long conversationId, Long messageId) {
        return messages.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new NotFoundException(
                        "Message %d was not found in conversation %d".formatted(messageId, conversationId)));
    }

    private static Long resolveTurnGroupId(ChatMessageEntity message) {
        return message.getTurnGroupId() != null ? message.getTurnGroupId() : message.getId();
    }

    /**
     * Builds the auto title of a conversation from its first user message.
     */
    String deriveTitle(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String flattened = content.strip().replaceAll("\\s+", " ");
        int limit = chatProperties.getTitleMaxLength();
        if (flattened.length() <= limit) {
            return flattened;
        }
        return flattened.substring(0, limit) + ELLIPSIS;
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

    private ConversationSummaryResponse toSummary(ConversationEntity conversation) {
        return toSummary(conversation, modelsForConversation(conversation));
    }

    private ConversationSummaryResponse toSummary(ConversationEntity conversation,
                                                  Map<Long, LlmModelEntity> modelById) {
        LlmModelEntity model = conversation.getModelId() == null
                ? null
                : modelById.get(conversation.getModelId());
        LlmModelEntity imageModel = conversation.getImageModelId() == null
                ? null
                : modelById.get(conversation.getImageModelId());
        return ConversationSummaryResponse.from(
                conversation,
                model == null ? null : model.getModelKey(),
                model == null ? null : displayNameOf(model),
                imageModel == null ? null : imageModel.getModelKey(),
                imageModel == null ? null : displayNameOf(imageModel));
    }

    private Map<Long, LlmModelEntity> modelsForConversation(ConversationEntity conversation) {
        java.util.HashSet<Long> ids = new java.util.HashSet<>(2);
        if (conversation.getModelId() != null) {
            ids.add(conversation.getModelId());
        }
        if (conversation.getImageModelId() != null) {
            ids.add(conversation.getImageModelId());
        }
        return ids.isEmpty() ? Map.of() : loadModelsById(ids);
    }

    private ChatMessageResponse toResponse(ChatMessageEntity message, int versionCount) {
        Map<Long, LlmModelEntity> modelById = message.getModelId() == null
                ? Map.of()
                : loadModelsById(Set.of(message.getModelId()));
        return toResponse(message, versionCount, modelById);
    }

    private ChatMessageResponse toResponse(ChatMessageEntity message,
                                           int versionCount,
                                           Map<Long, LlmModelEntity> modelById) {
        LlmModelEntity model = message.getModelId() == null ? null : modelById.get(message.getModelId());
        return ChatMessageResponse.from(
                message,
                attachmentsOf(message),
                versionCount,
                model == null ? null : model.getModelKey(),
                model == null ? null : displayNameOf(model));
    }

    private Map<Long, LlmModelEntity> loadModelsById(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return models.findAllById(ids).stream()
                .collect(Collectors.toMap(LlmModelEntity::getId, Function.identity(), (left, right) -> left));
    }

    private static String displayNameOf(LlmModelEntity model) {
        String name = model.getModelName();
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        return model.getModelKey();
    }
}
