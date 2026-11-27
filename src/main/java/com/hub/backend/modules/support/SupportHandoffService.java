package com.hub.backend.modules.support;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.agent.repository.AgentConversationRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.feedback.FeedbackService;
import com.hub.backend.modules.support.dto.SupportHandoffRequest;
import com.hub.backend.modules.support.dto.SupportHandoffResponse;
import com.hub.backend.modules.support.entity.SupportHandoffEntity;
import com.hub.backend.modules.support.repository.SupportHandoffRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class SupportHandoffService {

    private final SupportBotService botService;
    private final AgentConversationRepository conversations;
    private final AgentChatMessageRepository messages;
    private final SupportHandoffRepository handoffs;

    public SupportHandoffService(SupportBotService botService,
                                 AgentConversationRepository conversations,
                                 AgentChatMessageRepository messages,
                                 SupportHandoffRepository handoffs) {
        this.botService = botService;
        this.conversations = conversations;
        this.messages = messages;
        this.handoffs = handoffs;
    }

    @Transactional
    public SupportHandoffResponse handoff(CurrentUser user, SupportHandoffRequest request) {
        String content = request.content() == null ? "" : request.content().strip();
        if (content.length() < FeedbackService.MIN_CONTENT_LENGTH) {
            throw new InvalidRequestException("转人工说明至少需要 "
                    + FeedbackService.MIN_CONTENT_LENGTH + " 个字");
        }
        if (content.length() > FeedbackService.MAX_CONTENT_LENGTH) {
            throw new InvalidRequestException("转人工说明不能超过 "
                    + FeedbackService.MAX_CONTENT_LENGTH + " 个字");
        }

        Long conversationId = request.agentConversationId();
        String snapshot = null;
        if (conversationId != null) {
            AgentEntity agent = botService.requireAgent();
            AgentConversationEntity conversation = conversations
                    .findByIdAndUserIdAndAgentIdAndStatus(
                            conversationId,
                            user == null ? -1L : user.userId(),
                            agent.getId(),
                            AgentConversationEntity.STATUS_ACTIVE)
                    .orElseThrow(() -> new NotFoundException("客服会话不存在"));
            if (user != null && !conversation.getUserId().equals(user.userId())) {
                throw new NotFoundException("客服会话不存在");
            }
            snapshot = buildTranscript(conversation.getId());
        }

        SupportHandoffEntity entity = new SupportHandoffEntity();
        entity.setUserId(user == null ? null : user.userId());
        entity.setSiteId(user == null ? null : user.siteId());
        entity.setContent(content);
        entity.setContact(trimToNull(request.contact()));
        entity.setPageUrl(trimToNull(request.pageUrl()));
        entity.setAgentConversationId(conversationId);
        entity.setTranscriptSnapshot(snapshot);
        entity.setStatus(SupportHandoffEntity.STATUS_NEW);
        SupportHandoffEntity saved = handoffs.saveAndFlush(entity);
        return new SupportHandoffResponse(saved.getId(), saved.getCreatedAt());
    }

    private String buildTranscript(Long conversationId) {
        List<AgentChatMessageEntity> recent = messages
                .findByAgentConversationIdAndActiveTrueAndStatusOrderBySequenceNoDesc(
                        conversationId,
                        AgentChatMessageEntity.STATUS_COMPLETED,
                        PageRequest.of(0, SupportConstants.TRANSCRIPT_MAX_MESSAGES));
        List<AgentChatMessageEntity> chronological = new ArrayList<>(recent);
        Collections.reverse(chronological);
        StringBuilder sb = new StringBuilder();
        for (AgentChatMessageEntity message : chronological) {
            String role = message.getRole() == null ? "?" : message.getRole();
            String body = message.getContent() == null ? "" : message.getContent().strip();
            if (body.isEmpty()) {
                continue;
            }
            sb.append('[').append(role).append("] ").append(body).append("\n\n");
            if (sb.length() >= SupportConstants.TRANSCRIPT_MAX_CHARS) {
                sb.append("…(已截断)");
                break;
            }
        }
        String text = sb.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
