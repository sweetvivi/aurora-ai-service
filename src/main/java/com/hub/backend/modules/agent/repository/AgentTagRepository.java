package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AgentTagRepository extends JpaRepository<AgentTagEntity, Long> {

    List<AgentTagEntity> findByAgentIdOrderBySortOrderAscIdAsc(Long agentId);

    List<AgentTagEntity> findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(Collection<Long> agentIds);

    void deleteByAgentId(Long agentId);
}
