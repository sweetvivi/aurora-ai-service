package com.hub.backend.modules.auth.repository;

import com.hub.backend.modules.auth.entity.UserIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, Long> {

    Optional<UserIdentityEntity> findByProviderAndProviderSubject(String provider, String providerSubject);

    boolean existsByProviderAndProviderSubject(String provider, String providerSubject);

    void deleteByUserId(Long userId);
}
