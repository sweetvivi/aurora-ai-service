package com.hub.backend.modules.gateway.repository;

import com.hub.backend.modules.gateway.entity.UserGatewayTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserGatewayTokenRepository extends JpaRepository<UserGatewayTokenEntity, Long> {

    Optional<UserGatewayTokenEntity> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from UserGatewayTokenEntity token where token.userId = :userId")
    Optional<UserGatewayTokenEntity> findByUserIdForUpdate(@Param("userId") Long userId);
}
