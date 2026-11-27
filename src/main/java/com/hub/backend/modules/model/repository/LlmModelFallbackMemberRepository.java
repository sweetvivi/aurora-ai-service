package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmModelFallbackMemberRepository
        extends JpaRepository<LlmModelFallbackMemberEntity, Long> {

    List<LlmModelFallbackMemberEntity> findByGroupIdOrderByPriorityAscIdAsc(Long groupId);

    List<LlmModelFallbackMemberEntity> findByGroupIdInOrderByPriorityAscIdAsc(
            List<Long> groupIds);

    Optional<LlmModelFallbackMemberEntity> findByGroupIdAndModelId(Long groupId, Long modelId);

    Optional<LlmModelFallbackMemberEntity> findFirstByModelIdOrderByIdAsc(Long modelId);

    List<LlmModelFallbackMemberEntity> findByModelIdOrderByGroupIdAscPriorityAscIdAsc(
            Long modelId);

    void deleteByModelId(Long modelId);

    void deleteByGroupId(Long groupId);
}
