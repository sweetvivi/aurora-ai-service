package com.hub.backend.modules.agent.repository;

import com.hub.backend.modules.agent.entity.AgentCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentCategoryRepository extends JpaRepository<AgentCategoryEntity, Long> {

    List<AgentCategoryEntity> findBySiteIdAndStatusOrderBySortOrderAscIdAsc(Long siteId, String status);

    List<AgentCategoryEntity> findByStatusOrderBySiteIdAscSortOrderAscIdAsc(String status);
}
