package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.NewApiGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NewApiGroupRepository extends JpaRepository<NewApiGroupEntity, Long> {

    Optional<NewApiGroupEntity> findByGroupKey(String groupKey);

    List<NewApiGroupEntity> findByUsableTrueOrderByRatioAscGroupKeyAsc();

    List<NewApiGroupEntity> findAllByOrderByRatioAscGroupKeyAsc();
}
