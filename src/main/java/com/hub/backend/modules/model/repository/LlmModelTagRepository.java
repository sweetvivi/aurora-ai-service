package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.LlmModelTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface LlmModelTagRepository extends JpaRepository<LlmModelTagEntity, Long> {

    List<LlmModelTagEntity> findByModelIdInOrderBySortOrderAscIdAsc(Collection<Long> modelIds);

    /** Tags belong to their model, so they go when it goes. */
    void deleteByModelId(Long modelId);
}
