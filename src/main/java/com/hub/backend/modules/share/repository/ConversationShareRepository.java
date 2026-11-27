package com.hub.backend.modules.share.repository;

import com.hub.backend.modules.share.entity.ConversationShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationShareRepository extends JpaRepository<ConversationShareEntity, Long> {

    Optional<ConversationShareEntity> findByTokenHash(String tokenHash);

    List<ConversationShareEntity> findByConversationIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long conversationId);

    Optional<ConversationShareEntity> findByIdAndConversationIdAndUserId(Long id, Long conversationId, Long userId);
}
