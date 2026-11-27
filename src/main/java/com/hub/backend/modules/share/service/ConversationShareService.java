package com.hub.backend.modules.share.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.conversation.ConversationService;
import com.hub.backend.modules.conversation.dto.ChatMessageResponse;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.share.dto.ConversationShareCreateRequest;
import com.hub.backend.modules.share.dto.ConversationShareResponse;
import com.hub.backend.modules.share.dto.SharedConversationResponse;
import com.hub.backend.modules.share.entity.ConversationShareEntity;
import com.hub.backend.modules.share.repository.ConversationShareRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;


@Service
public class ConversationShareService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConversationService conversationService;
    private final ConversationShareRepository shares;
    private final ObjectMapper objectMapper;

    public ConversationShareService(ConversationService conversationService,
                                    ConversationShareRepository shares,
                                    ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.shares = shares;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConversationShareResponse create(Long conversationId, Long userId,
                                            ConversationShareCreateRequest request) {
        ConversationEntity conversation = conversationService.requireOwned(conversationId, userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = resolveExpiry(request, now);

        List<ChatMessageResponse> shareable = conversationService.listShareableMessages(conversationId).stream()
                .map(ChatMessageResponse::from)
                .toList();
        SharedConversationResponse snapshot = new SharedConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                shareable.size(),
                conversation.getCreatedAt(),
                shareable);

        String token = randomToken();
        ConversationShareEntity saved = shares.save(new ConversationShareEntity(
                conversationId,
                userId,
                tokenHash(token),
                conversation.getTitle(),
                expiresAt,
                toJson(snapshot)));
        return new ConversationShareResponse(
                saved.getId(),
                saved.getConversationId(),
                "/api/v1/shares/" + token,
                token,
                saved.getTitle(),
                saved.getExpiresAt(),
                saved.getRevokedAt(),
                saved.getCreatedAt());
    }

    @Transactional
    public List<ConversationShareResponse> list(Long conversationId, Long userId) {
        conversationService.requireOwned(conversationId, userId);
        return shares.findByConversationIdAndRevokedAtIsNullOrderByCreatedAtDesc(conversationId)
                .stream()
                .map(ConversationShareResponse::from)
                .toList();
    }

    @Transactional
    public void revoke(Long conversationId, Long shareId, Long userId) {
        ConversationShareEntity share = shares.findByIdAndConversationIdAndUserId(shareId, conversationId, userId)
                .orElseThrow(() -> new NotFoundException("Share %d was not found".formatted(shareId)));
        if (share.getRevokedAt() == null) {
            share.revoke(LocalDateTime.now());
        }
    }

    @Transactional(readOnly = true)
    public SharedConversationResponse read(String token) {
        return shares.findByTokenHash(tokenHash(token))
                .filter(share -> share.getRevokedAt() == null)
                .filter(share -> !isExpired(share.getExpiresAt(), LocalDateTime.now()))
                .map(this::toSnapshot)
                .orElseThrow(() -> new NotFoundException("Conversation share '%s' was not found".formatted(token)));
    }

    private static LocalDateTime resolveExpiry(ConversationShareCreateRequest request, LocalDateTime now) {
        if (request == null || request.expiresInSeconds() == null) {
            return null;
        }
        return now.plusSeconds(request.expiresInSeconds());
    }

    private static boolean isExpired(LocalDateTime expiresAt, LocalDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new InvalidRequestException("Unable to hash the share token");
        }
    }

    private String toJson(SharedConversationResponse snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialise the share snapshot", exception);
        }
    }

    private SharedConversationResponse toSnapshot(ConversationShareEntity share) {
        try {
            return objectMapper.readValue(share.getSnapshotJson(), SharedConversationResponse.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read the share snapshot", exception);
        }
    }
}
