package com.hub.backend.modules.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_doc_chunks")
public class SupportDocChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    protected SupportDocChunkEntity() {
    }

    public SupportDocChunkEntity(Long docId, int chunkIndex, String content) {
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public Long getDocId() {
        return docId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }
}
