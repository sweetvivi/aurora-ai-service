package com.hub.backend.modules.support.repository;

import com.hub.backend.modules.support.entity.SupportHandoffEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportHandoffRepository extends JpaRepository<SupportHandoffEntity, Long> {

    long countByStatus(String status);

    @Query("""
            select h from SupportHandoffEntity h
            where (:status is null or h.status = :status)
              and (
                   :keyword is null
                   or lower(h.content) like :keyword
                   or lower(coalesce(h.contact, '')) like :keyword
                   or cast(h.id as string) = :raw
                   or cast(h.userId as string) = :raw
                   or cast(h.agentConversationId as string) = :raw
              )
            """)
    Page<SupportHandoffEntity> searchAdmin(@Param("status") String status,
                                           @Param("keyword") String keyword,
                                           @Param("raw") String raw,
                                           Pageable pageable);
}
