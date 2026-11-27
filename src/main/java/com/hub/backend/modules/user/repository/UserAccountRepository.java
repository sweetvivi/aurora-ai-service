package com.hub.backend.modules.user.repository;

import com.hub.backend.modules.user.entity.UserAccountEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, Long> {

    Optional<UserAccountEntity> findFirstBySiteIdAndUsername(Long siteId, String username);

    boolean existsBySiteIdAndUsername(Long siteId, String username);

    boolean existsBySiteIdAndEmailAndIdNot(Long siteId, String email, Long id);

    boolean existsBySiteIdAndEmail(Long siteId, String email);

    boolean existsBySiteIdAndPhoneAndIdNot(Long siteId, String phone, Long id);

    @Query("""
            select u from UserAccountEntity u
            where u.siteId = :siteId
              and (:status is null or upper(u.status) = :status)
              and (:keyword is null
                   or lower(coalesce(u.username, '')) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(u.nickname, '')) like lower(concat('%', :keyword, '%')))
            """)
    Page<UserAccountEntity> searchAdminUsers(@Param("siteId") Long siteId,
                                             @Param("keyword") String keyword,
                                             @Param("status") String status,
                                             Pageable pageable);

    List<UserAccountEntity> findByIdIn(Collection<Long> ids);

    /**
     * 只写 {@code last_login_at} 一行一列。
     *
     * <p>登录不再整实体 {@code save}：merge 会把所有列写回去，锁面更大，与「建会话」的事务相交时
     * 就是一条死锁链（压测实测 1213）。一次定向更新只碰这一行、这一列，提交即释放。</p>
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserAccountEntity u set u.lastLoginAt = :now where u.id = :userId")
    int touchLastLogin(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
