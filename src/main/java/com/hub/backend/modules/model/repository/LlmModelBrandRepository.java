package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.LlmModelBrandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmModelBrandRepository extends JpaRepository<LlmModelBrandEntity, Long> {

    Optional<LlmModelBrandEntity> findByBrandKey(String brandKey);

    boolean existsByBrandKey(String brandKey);

    boolean existsByBrandKeyAndIdNot(String brandKey, Long id);

    List<LlmModelBrandEntity> findAllByOrderBySortOrderAscIdAsc();

    List<LlmModelBrandEntity> findByStatusOrderBySortOrderAscIdAsc(String status);
}
