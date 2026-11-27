package com.hub.backend.modules.support;

import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.admin.operations.AdminListResponse;
import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.agent.repository.AgentConversationRepository;
import com.hub.backend.modules.support.dto.SupportConversationMessageResponse;
import com.hub.backend.modules.support.dto.SupportConversationSummaryResponse;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SupportConversationAdminService {

    private final SupportBotService botService;
    private final AgentConversationRepository conversations;
    private final AgentChatMessageRepository messages;
    private final UserAccountRepository accounts;

    public SupportConversationAdminService(SupportBotService botService,
                                           AgentConversationRepository conversations,
                                           AgentChatMessageRepository messages,
                                           UserAccountRepository accounts) {
        this.botService = botService;
        this.conversations = conversations;
        this.messages = messages;
        this.accounts = accounts;
    }

    public AdminListResponse<SupportConversationSummaryResponse> list(String keyword,
                                                                      int page,
                                                                      int pageSize) {
        AgentEntity agent = botService.requireAgent();
        int safePage = Math.max(page, 1);
        int safeSize = pageSize < 1 ? 10 : Math.min(pageSize, 100);
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id")));

        String raw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        String like = raw == null ? null : "%" + raw.toLowerCase(Locale.ROOT) + "%";
        Page<AgentConversationEntity> result = conversations.searchByAgentId(
                agent.getId(),
                AgentConversationEntity.STATUS_ACTIVE,
                like,
                raw,
                pageable);

        Map<Long, String> usernames = usernames(result.getContent().stream()
                .map(AgentConversationEntity::getUserId)
                .collect(Collectors.toSet()));

        List<SupportConversationSummaryResponse> items = result.getContent().stream()
                .map(row -> new SupportConversationSummaryResponse(
                        row.getId(),
                        row.getUserId(),
                        usernames.getOrDefault(row.getUserId(), ""),
                        row.getTitle() == null ? "客服会话" : row.getTitle(),
                        row.getMessageCount(),
                        row.getLastMessageAt(),
                        row.getCreatedAt(),
                        row.getUpdatedAt()))
                .toList();
        return new AdminListResponse<>(items, result.getTotalElements());
    }

    public List<SupportConversationMessageResponse> messages(Long conversationId) {
        AgentEntity agent = botService.requireAgent();
        AgentConversationEntity conversation = conversations.findById(conversationId)
                .filter(row -> Objects.equals(row.getAgentId(), agent.getId()))
                .filter(row -> AgentConversationEntity.STATUS_ACTIVE.equals(row.getStatus()))
                .orElseThrow(() -> new NotFoundException("客服会话不存在"));

        return messages.findByAgentConversationIdOrderBySequenceNoAsc(conversation.getId()).stream()
                .filter(AgentChatMessageEntity::isActive)
                .map(message -> new SupportConversationMessageResponse(
                        message.getId(),
                        message.getRole(),
                        message.getContent() == null ? "" : message.getContent(),
                        message.getStatus(),
                        message.getSequenceNo(),
                        message.getCreatedAt()))
                .toList();
    }

    private Map<Long, String> usernames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accounts.findAllById(ids).stream()
                .collect(Collectors.toMap(
                        UserAccountEntity::getId,
                        account -> account.getUsername() == null ? "" : account.getUsername(),
                        (left, right) -> left));
    }
}
