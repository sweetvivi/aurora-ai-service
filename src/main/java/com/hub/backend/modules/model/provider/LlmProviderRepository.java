package com.hub.backend.modules.model.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, Long> {

    Optional<LlmProviderEntity> findByProviderKey(String providerKey);

    Optional<LlmProviderEntity> findBySourceAndExternalId(String source, String externalId);

    List<LlmProviderEntity> findBySourceOrderByPriorityDescWeightDescExternalIdAsc(String source);

    boolean existsByProviderKey(String providerKey);
}
