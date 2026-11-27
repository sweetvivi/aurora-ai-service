package com.hub.backend.modules.gateway.repository;

import com.hub.backend.modules.gateway.entity.NewApiAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewApiAccountRepository extends JpaRepository<NewApiAccountEntity, Long> {

    Optional<NewApiAccountEntity> findByUserId(Long userId);

    Optional<NewApiAccountEntity> findByNewapiUserId(Long newapiUserId);
}
