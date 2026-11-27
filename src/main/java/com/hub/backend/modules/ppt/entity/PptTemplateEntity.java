package com.hub.backend.modules.ppt.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "ppt_templates", uniqueConstraints = @UniqueConstraint(name = "uk_ppt_templates_template_id", columnNames = "template_id"))
public class PptTemplateEntity {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String DISABLED = "DISABLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="template_id", nullable=false, length=64) private String templateId;
    @Column(nullable=false, length=255) private String name;
    @Column(nullable=false, length=128) private String category;
    @Column(length=2048) private String description;
    @Column(name="scenes_json", columnDefinition="LONGTEXT") private String scenesJson;
    @Column(name="layouts_json", columnDefinition="LONGTEXT") private String layoutsJson;
    @Column(name="theme_json", columnDefinition="LONGTEXT") private String themeJson;
    @Column(name="source_storage_key", length=512) private String sourceStorageKey;
    @Column(name="source_file_name", length=512) private String sourceFileName;
    @Column(name="preview_url", length=1024) private String previewUrl;
    @Column(nullable=false, length=32) private String status = DRAFT;
    @CreationTimestamp @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
    protected PptTemplateEntity() {}
    public PptTemplateEntity(String templateId, String name, String category) { this.templateId=templateId; this.name=name; this.category=category; }
    public Long getId(){return id;} public String getTemplateId(){return templateId;} public String getName(){return name;} public String getCategory(){return category;} public String getDescription(){return description;} public String getScenesJson(){return scenesJson;} public String getLayoutsJson(){return layoutsJson;} public String getThemeJson(){return themeJson;} public String getSourceStorageKey(){return sourceStorageKey;} public String getSourceFileName(){return sourceFileName;} public String getPreviewUrl(){return previewUrl;} public String getStatus(){return status;} public LocalDateTime getUpdatedAt(){return updatedAt;}
    public void setName(String v){name=v;} public void setCategory(String v){category=v;} public void setDescription(String v){description=v;} public void setScenesJson(String v){scenesJson=v;} public void setLayoutsJson(String v){layoutsJson=v;} public void setThemeJson(String v){themeJson=v;} public void setSourceStorageKey(String v){sourceStorageKey=v;} public void setSourceFileName(String v){sourceFileName=v;} public void setPreviewUrl(String v){previewUrl=v;} public void setStatus(String v){status=v;}
}
