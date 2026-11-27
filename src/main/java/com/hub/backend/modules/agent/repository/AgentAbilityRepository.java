package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentAbilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AgentAbilityRepository extends JpaRepository<AgentAbilityEntity, Long> {

    List<AgentAbilityEntity> findByAgentIdOrderBySortOrderAscIdAsc(Long agentId);

    List<AgentAbilityEntity> findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(Collection<Long> agentIds);

    void deleteByAgentId(Long agentId);
}
