package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LlmModelGroupRepository extends JpaRepository<LlmModelGroupEntity, Long> {

    Optional<LlmModelGroupEntity> findFirstBySiteIdIsNullAndGroupKeyOrderByIdAsc(String groupKey);

    /** Groups a site may see: global rows plus its own, in display order. */
    @Query("select g from LlmModelGroupEntity g "
            + "where g.status = :status and (g.siteId is null or g.siteId = :siteId) "
            + "order by g.sortOrder asc, g.id asc")
    List<LlmModelGroupEntity> findVisible(@Param("siteId") Long siteId, @Param("status") String status);
}
