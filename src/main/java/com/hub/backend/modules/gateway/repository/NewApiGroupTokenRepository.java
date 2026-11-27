package com.hub.backend.modules.gateway.repository;

import com.hub.backend.modules.gateway.entity.NewApiGroupTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NewApiGroupTokenRepository
        extends JpaRepository<NewApiGroupTokenEntity, Long> {

    /**
     * 取行锁后再改：并发的两个请求可能同时用到同一个分组，只有一个能拿到租约。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from NewApiGroupTokenEntity t "
            + "where t.userId = :userId and t.groupKey = :groupKey")
    Optional<NewApiGroupTokenEntity> findByUserIdAndGroupKeyForUpdate(
            @Param("userId") Long userId, @Param("groupKey") String groupKey);

    List<NewApiGroupTokenEntity> findByUserId(Long userId);
}
