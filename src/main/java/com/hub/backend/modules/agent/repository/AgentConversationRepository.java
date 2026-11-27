package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversationEntity, Long> {

    Page<AgentConversationEntity> findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long userId, String status, Pageable pageable);

    Page<AgentConversationEntity> findByUserIdAndAgentIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long userId, Long agentId, String status, Pageable pageable);

    @Query("""
            select conversation
              from AgentConversationEntity conversation, AgentEntity agent
             where conversation.agentId = agent.id
               and conversation.userId = :userId
               and conversation.status = :conversationStatus
               and agent.categoryId = :categoryId
               and (:searchText is null
                    or lower(coalesce(conversation.title, '')) like :searchText
                    or lower(agent.name) like :searchText)
             order by conversation.updatedAt desc, conversation.id desc
            """)
    Page<AgentConversationEntity> findByUserIdAndCategoryId(
            @Param("userId") Long userId,
            @Param("categoryId") Long categoryId,
            @Param("conversationStatus") String conversationStatus,
            @Param("searchText") String searchText,
            Pageable pageable);

    Optional<AgentConversationEntity> findByIdAndUserIdAndAgentIdAndStatus(
            Long id, Long userId, Long agentId, String status);

    Optional<AgentConversationEntity> findByIdAndUserIdAndStatus(Long id, Long userId, String status);

    Page<AgentConversationEntity> findByAgentIdAndStatusOrderByUpdatedAtDescIdDesc(
            Long agentId, String status, Pageable pageable);

    @Query("""
            select conversation
              from AgentConversationEntity conversation
             where conversation.agentId = :agentId
               and conversation.status = :status
               and (:keyword is null
                    or lower(coalesce(conversation.title, '')) like :keyword
                    or cast(conversation.id as string) = :rawKeyword
                    or cast(conversation.userId as string) = :rawKeyword
                    or exists (
                        select u.id from UserAccountEntity u
                         where u.id = conversation.userId
                           and lower(coalesce(u.username, '')) like :keyword
                    ))
             order by conversation.updatedAt desc, conversation.id desc
            """)
    Page<AgentConversationEntity> searchByAgentId(
            @Param("agentId") Long agentId,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("rawKeyword") String rawKeyword,
            Pageable pageable);
}
