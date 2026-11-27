package com.hub.backend.modules.banner.repository;

import com.hub.backend.modules.banner.entity.SiteBannerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SiteBannerRepository extends JpaRepository<SiteBannerEntity, Long> {

    List<SiteBannerEntity> findAllByOrderBySortOrderAscIdAsc();

    @Query("""
            select banner
              from SiteBannerEntity banner
             where banner.status = :status
               and (banner.siteId = :siteId or banner.siteId is null)
               and (banner.startAt is null or banner.startAt <= :now)
               and (banner.endAt is null or banner.endAt > :now)
             order by case when banner.siteId = :siteId then 0 else 1 end,
                      banner.sortOrder asc,
                      banner.id asc
            """)
    List<SiteBannerEntity> findCurrentCandidates(@Param("status") String status,
                                                 @Param("siteId") Long siteId,
                                                 @Param("now") LocalDateTime now);
}
