package com.hub.backend.modules.support.repository;

import com.hub.backend.modules.support.entity.SupportDocChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SupportDocChunkRepository extends JpaRepository<SupportDocChunkEntity, Long> {

    List<SupportDocChunkEntity> findByDocIdIn(Collection<Long> docIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SupportDocChunkEntity c where c.docId = :docId")
    void deleteByDocId(@Param("docId") Long docId);
}
