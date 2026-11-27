package com.hub.backend.modules.conversation.repository;

import com.hub.backend.modules.conversation.entity.ChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /**
     * @return highest sequence number used in the conversation, {@code 0} when it is still empty
     */
    @Query("select coalesce(max(m.sequenceNo), 0) from ChatMessageEntity m where m.conversationId = :conversationId")
    int findMaxSequenceNo(@Param("conversationId") Long conversationId);

    @Query("""
            select coalesce(max(m.version), 0) from ChatMessageEntity m
            where m.conversationId = :conversationId and m.turnGroupId = :turnGroupId
            """)
    int findMaxVersion(@Param("conversationId") Long conversationId,
                       @Param("turnGroupId") Long turnGroupId);

    List<ChatMessageEntity> findByConversationIdOrderBySequenceNoAsc(Long conversationId);

    List<ChatMessageEntity> findByConversationIdAndActiveTrueOrderBySequenceNoAsc(Long conversationId);

    Optional<ChatMessageEntity>
            findFirstByConversationIdAndActiveTrueAndRoleAndArtifactContextJsonIsNotNullOrderBySequenceNoDesc(
            Long conversationId, String role);

    List<ChatMessageEntity> findByConversationIdAndTurnGroupIdOrderByVersionAsc(
            Long conversationId, Long turnGroupId);

    List<ChatMessageEntity> findByConversationIdAndTurnGroupIdInOrderByTurnGroupIdAscVersionAsc(
            Long conversationId, Collection<Long> turnGroupIds);

    List<ChatMessageEntity> findByConversationIdAndSequenceNoGreaterThanEqual(
            Long conversationId, int sequenceNo);

    List<ChatMessageEntity> findByConversationIdAndSequenceNoGreaterThan(
            Long conversationId, int sequenceNo);

    List<ChatMessageEntity> findByConversationIdAndReplyToMessageIdOrderBySequenceNoAsc(
            Long conversationId, Long replyToMessageId);

    Optional<ChatMessageEntity> findByIdAndConversationId(Long id, Long conversationId);

    long countByConversationId(Long conversationId);

    /** @return how many turns were produced by a catalogue model, used to refuse deleting it */
    long countByModelId(Long modelId);

    /**
     * Keyset pagination by message id (newer than the cursor).
     *
     * <p>Within one conversation {@code id} and {@code sequence_no} grow together and the id is the
     * primary key, so a cursor page stays cheap however deep the client scrolls. Pass {@code 0} to
     * start from the beginning.</p>
     */
    List<ChatMessageEntity> findByConversationIdAndIdGreaterThanOrderByIdAsc(
            Long conversationId, Long cursor, Pageable pageable);

    /** Newest messages first; used for the initial open of a thread. */
    List<ChatMessageEntity> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    /** Older than {@code before}, newest-first within the page. */
    List<ChatMessageEntity> findByConversationIdAndIdLessThanOrderByIdDesc(
            Long conversationId, Long before, Pageable pageable);
}
