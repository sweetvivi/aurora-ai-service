package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentFavoriteRepository extends JpaRepository<AgentFavoriteEntity, Long> {

    Optional<AgentFavoriteEntity> findByUserIdAndAgentId(Long userId, Long agentId);

    List<AgentFavoriteEntity> findByUserIdAndAgentIdIn(Long userId, Collection<Long> agentIds);

    List<AgentFavoriteEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    long deleteByUserIdAndAgentId(Long userId, Long agentId);
}
