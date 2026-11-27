package com.hub.backend.modules.feedback.repository;

import com.hub.backend.modules.feedback.entity.UserFeedbackEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFeedbackRepository extends JpaRepository<UserFeedbackEntity, Long> {

    long countByStatus(String status);

    /**
     * Admin listing. Every filter is optional.
     *
     * <p>The keyword also matches the account name and the row id: 运营手里通常只有「某个用户说的」
     * 或「我上次记下的编号」，两个都能直接搜到，不必先切到用户管理去查 id。
     */
    @Query("""
            select f from UserFeedbackEntity f
            where (:status is null or f.status = :status)
              and (:category is null or f.category = :category)
              and (:source is null or f.source = :source)
              and (:keyword is null
                   or lower(f.content) like lower(concat('%', :keyword, '%'))
                   or lower(coalesce(f.contact, '')) like lower(concat('%', :keyword, '%'))
                   or cast(f.id as string) = :keyword
                   or exists (
                       select u.id from UserAccountEntity u
                       where u.id = f.userId
                         and lower(coalesce(u.username, '')) like lower(concat('%', :keyword, '%'))
                   ))
            """)
    Page<UserFeedbackEntity> searchAdmin(@Param("status") String status,
                                         @Param("category") String category,
                                         @Param("source") String source,
                                         @Param("keyword") String keyword,
                                         Pageable pageable);
}
