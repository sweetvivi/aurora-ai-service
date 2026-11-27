package com.hub.backend.modules.file.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A work hidden from one page's list without deleting the picture.
 *
 * <p>Deleting is only honest on the pages that own the work (image generation, AI drawing): there
 * the row and the bytes go away and every gallery loses it at once. The smart-editing workbench
 * and the canvas material library present the same pictures as <em>material</em>, and removing one
 * there must not destroy a picture the user still has elsewhere. A per-scope hide row keeps the
 * attachment intact while taking it out of that one list.</p>
 */
@Entity
@Table(name = "hidden_works")
public class HiddenWorkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    /** Which list hides it: {@code smart_edit} today, other pages can adopt the same mechanism. */
    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected HiddenWorkEntity() {
    }

    public HiddenWorkEntity(Long userId, Long fileId, String scope) {
        this.userId = userId;
        this.fileId = fileId;
        this.scope = scope;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getFileId() {
        return fileId;
    }

    public String getScope() {
        return scope;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
