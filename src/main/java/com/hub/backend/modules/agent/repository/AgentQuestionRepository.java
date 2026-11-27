package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AgentQuestionRepository extends JpaRepository<AgentQuestionEntity, Long> {

    List<AgentQuestionEntity> findByAgentIdOrderBySortOrderAscIdAsc(Long agentId);

    List<AgentQuestionEntity> findByAgentIdInOrderByAgentIdAscSortOrderAscIdAsc(Collection<Long> agentIds);

    void deleteByAgentId(Long agentId);
}
