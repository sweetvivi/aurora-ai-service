package com.hub.backend.modules.conversation.repository;

import com.hub.backend.modules.conversation.entity.ConversationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every finder is scoped by owner: querying a thread by id alone would let one account read
 * another account's history (see the IDOR note in {@code docs/project-audit.md}).
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    Page<ConversationEntity> findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long userId, String status, Pageable pageable);

    /**
     * History list restricted to one kind of thread.
     *
     * <p>Drawing threads ({@code PAINTING}) live in this table too; without the type predicate they
     * would show up in the chat history as threads whose only "message" is an image prompt.</p>
     */
    Page<ConversationEntity> findByUserIdAndStatusAndConversationTypeOrderByUpdatedAtDescIdDesc(
            Long userId, String status, String conversationType, Pageable pageable);

    Optional<ConversationEntity> findByIdAndUserIdAndStatus(Long id, Long userId, String status);

    /**
     * Batch counterpart of {@link #findByIdAndUserIdAndStatus(Long, Long, String)}.
     *
     * <p>The owner stays part of the query, so a batch can only ever touch this account's threads:
     * ids that are unknown or belong to somebody else simply do not come back, and the caller learns
     * how many were skipped without being able to tell those two cases apart.</p>
     */
    List<ConversationEntity> findByIdInAndUserIdAndStatus(
            Collection<Long> ids, Long userId, String status);

    /** @return how many threads reference a catalogue model, used to refuse deleting it */
    long countByModelId(Long modelId);

    @Query("""
            select c from ConversationEntity c
            where c.status <> :deletedStatus
              and (:keyword is null
                   or lower(coalesce(c.title, '')) like lower(concat('%', :keyword, '%'))
                   or exists (
                       select u.id from UserAccountEntity u
                       where u.id = c.userId
                         and lower(coalesce(u.username, '')) like lower(concat('%', :keyword, '%'))
                   ))
            """)
    Page<ConversationEntity> searchAdminConversations(@Param("keyword") String keyword,
                                                      @Param("deletedStatus") String deletedStatus,
                                                      Pageable pageable);

    Optional<ConversationEntity> findFirstByIdAndStatusNot(Long id, String status);
}
