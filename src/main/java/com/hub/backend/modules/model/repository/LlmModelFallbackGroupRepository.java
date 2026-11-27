package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LlmModelFallbackGroupRepository
        extends JpaRepository<LlmModelFallbackGroupEntity, Long> {

    Optional<LlmModelFallbackGroupEntity> findBySiteIdIsNullAndGroupKey(String groupKey);

    Optional<LlmModelFallbackGroupEntity> findBySiteIdAndGroupKey(Long siteId, String groupKey);

    List<LlmModelFallbackGroupEntity> findAllByOrderBySortOrderAscIdAsc();

    List<LlmModelFallbackGroupEntity> findBySiteIdOrderBySortOrderAscIdAsc(Long siteId);

    List<LlmModelFallbackGroupEntity> findBySiteIdIsNullOrderBySortOrderAscIdAsc();

    @Query("select g from LlmModelFallbackGroupEntity g "
            + "where g.status = :status and (g.siteId is null or g.siteId = :siteId) "
            + "order by g.sortOrder asc, g.id asc")
    List<LlmModelFallbackGroupEntity> findVisible(@Param("siteId") Long siteId,
                                                  @Param("status") String status);

    @Query("select g from LlmModelFallbackGroupEntity g "
            + "where g.status = :status and g.modelType = :modelType "
            + "and (g.siteId is null or g.siteId = :siteId) "
            + "order by g.sortOrder asc, g.id asc")
    List<LlmModelFallbackGroupEntity> findVisibleByType(@Param("siteId") Long siteId,
                                                        @Param("modelType") String modelType,
                                                        @Param("status") String status);

    @Query("select g from LlmModelFallbackGroupEntity g "
            + "where g.groupKey = :groupKey and (g.siteId is null or g.siteId = :siteId) "
            + "order by case when g.siteId = :siteId then 0 else 1 end, g.id asc")
    List<LlmModelFallbackGroupEntity> findVisibleByGroupKeyForResolution(
            @Param("siteId") Long siteId,
            @Param("groupKey") String groupKey);
}
