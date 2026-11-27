package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AgentRepository extends JpaRepository<AgentEntity, Long>, JpaSpecificationExecutor<AgentEntity> {

    Optional<AgentEntity> findFirstBySnAndStatus(String sn, String status);

    Optional<AgentEntity> findByIdAndStatus(Long id, String status);

    boolean existsBySiteIdAndAgentKey(Long siteId, String agentKey);

    boolean existsBySn(String sn);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentEntity a set a.seeCount = a.seeCount + 1 where a.id = :agentId")
    int incrementSeeCount(@Param("agentId") Long agentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentEntity a set a.useCount = a.useCount + 1 where a.id = :agentId")
    int incrementUseCount(@Param("agentId") Long agentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentEntity a set a.favoriteCount = a.favoriteCount + 1 where a.id = :agentId")
    int incrementFavoriteCount(@Param("agentId") Long agentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentEntity a
               set a.favoriteCount = case when a.favoriteCount > 0 then a.favoriteCount - 1 else 0 end
             where a.id = :agentId
            """)
    int decrementFavoriteCount(@Param("agentId") Long agentId);
}
