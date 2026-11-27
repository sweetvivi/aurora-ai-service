package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentModelRepository extends JpaRepository<AgentModelEntity, Long> {

    List<AgentModelEntity> findByAgentIdOrderBySortOrderAscIdAsc(Long agentId);

    List<AgentModelEntity> findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(Collection<Long> agentIds);

    Optional<AgentModelEntity> findFirstByAgentIdAndDefaultModelTrueOrderBySortOrderAscIdAsc(Long agentId);

    /**
     * 先删后插同一 (agent_id, model_id) 时必须立刻 flush，否则 Hibernate 可能先 INSERT 触发唯一约束。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AgentModelEntity m where m.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") Long agentId);
}
