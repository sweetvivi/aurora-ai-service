package com.hub.backend.modules.model.repository;

import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LlmModelRepository extends JpaRepository<LlmModelEntity, Long> {

    /**
     * Resolves a catalogue entry by its public key.
     *
     * <p>Uniqueness is scoped per site, so a global row is preferred simply by insertion order.</p>
     */
    Optional<LlmModelEntity> findFirstByModelKeyOrderByIdAsc(String modelKey);

    Optional<LlmModelEntity> findBySourceAndExternalId(String source, String externalId);

    List<LlmModelEntity> findBySource(String source);

    List<LlmModelEntity> findByModelKeyOrderByIdAsc(String modelKey);

    List<LlmModelEntity> findByProviderId(Long providerId);

    long countByModelTypeAndStatus(String modelType, String status);

    /**
     * Whether the catalogue has ever heard of this model type, <em>anywhere</em>, regardless of
     * status, upstream availability or site.
     *
     * <p>Distinguishes two cases that both end up as "no visible row carries this key": the
     * catalogue has never seen the type at all (first boot, failed sync — every key looks unlisted,
     * so refusing them would take the whole capability down) and the catalogue does know the type
     * but not this key (the catalogue is authoritative, so the key is refused). Filtering by status
     * would merge them, because a retired or hidden row is exactly the case that must still count
     * as "known". Filtering by site would too: a row that belongs to another site must stay
     * invisible to this one, not become "unknown" and therefore forwarded upstream.</p>
     */
    @Query("select count(m) from LlmModelEntity m where upper(m.modelType) = upper(:modelType)")
    long countByTypeForResolution(@Param("modelType") String modelType);

    /**
     * Models a site may use: global rows ({@code site_id is null}) plus its own.
     *
     * <p>The same visibility rule is used for the catalogue list and for the single model lookup,
     * so a key never resolves to one row in the list and another in the detail view.</p>
     */
    @Query("select m from LlmModelEntity m "
            + "where m.status = :status and m.upstreamAvailable = true "
            + "and (m.siteId is null or m.siteId = :siteId) "
            + "order by m.sortOrder asc, m.id asc")
    List<LlmModelEntity> findVisible(@Param("siteId") Long siteId, @Param("status") String status);

    /**
     * Single model lookup under the same visibility rule. Ordering by id keeps the winner identical
     * to {@link #findFirstByModelKeyOrderByIdAsc(String)} used by the chat path.
     */
    @Query("select m from LlmModelEntity m "
            + "where m.modelKey = :modelKey and m.status = :status and m.upstreamAvailable = true "
            + "and (m.siteId is null or m.siteId = :siteId) "
            + "order by m.id asc")
    List<LlmModelEntity> findVisibleByModelKey(@Param("siteId") Long siteId,
                                               @Param("modelKey") String modelKey,
                                               @Param("status") String status);

    /**
     * Same visibility rule as the catalogue, but without the status filter.
     *
     * <p>The chat path needs to tell two cases apart that a status filter would merge: "a visible
     * entry exists but was retired" must be a 404, while "no visible entry at all" is forwarded
     * upstream so a new model can be used before the catalogue catches up.</p>
     */
    @Query("select m from LlmModelEntity m "
            + "where m.modelKey = :modelKey and (m.siteId is null or m.siteId = :siteId) "
            + "order by m.id asc")
    List<LlmModelEntity> findVisibleByModelKeyForResolution(@Param("siteId") Long siteId,
                                                            @Param("modelKey") String modelKey);

    /** @return the enabled default model, used by the app configuration payload */
    List<LlmModelEntity> findByStatusOrderBySortOrderAscIdAsc(String status);

    /** @return how many models point at this supplier, used to refuse deleting it */
    long countByProviderId(Long providerId);

    /** @return how many models are variants of this one */
    long countByParentModelId(Long parentModelId);

    /** Companies on every catalogue row; used to count models linked to a brand. */
    @Query("select m.company from LlmModelEntity m where m.company is not null and m.company <> ''")
    List<String> findAllCompanies();
}
