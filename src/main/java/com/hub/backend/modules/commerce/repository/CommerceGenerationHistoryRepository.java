package com.hub.backend.modules.commerce.repository;

import com.hub.backend.modules.commerce.entity.CommerceGenerationHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommerceGenerationHistoryRepository
        extends JpaRepository<CommerceGenerationHistoryEntity, Long> {

    Page<CommerceGenerationHistoryEntity> findBySiteIdAndUserIdOrderByCreatedAtDescIdDesc(
            Long siteId, Long userId, Pageable pageable);

    Optional<CommerceGenerationHistoryEntity> findByIdAndSiteIdAndUserId(
            Long id, Long siteId, Long userId);

    long deleteBySiteIdAndUserId(Long siteId, Long userId);

    /**
     * 结果地址里含某段路径的历史记录。
     *
     * <p>历史表只存 {@code result_url} 字符串，没有外键，所以判断「这张图还在不在用」只能靠
     * 地址前缀匹配（片段形如 {@code /files/123/content}）。删除确认与后台清理共用这里。</p>
     */
    @Query("""
            select history
              from CommerceGenerationHistoryEntity history
             where history.userId = :userId
               and history.resultUrl like concat('%', :fragment, '%')
            """)
    List<CommerceGenerationHistoryEntity> findByResultUrlFragment(
            @Param("userId") Long userId, @Param("fragment") String fragment);
}
