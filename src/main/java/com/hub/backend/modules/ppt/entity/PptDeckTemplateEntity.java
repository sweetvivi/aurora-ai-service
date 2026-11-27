package com.hub.backend.modules.ppt.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "ppt_deck_templates")
public class PptDeckTemplateEntity {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_ARCHIVED = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "category", nullable = false, length = 64)
    private String category = "商务";

    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "style_blurb", nullable = false, length = 512)
    private String styleBlurb = "";

    @Column(name = "cover_url", nullable = false, length = 512)
    private String coverUrl = "";

    @Column(name = "aspect_ratio", nullable = false, length = 16)
    private String aspectRatio = "16:9";

    @Column(name = "page_min", nullable = false)
    private int pageMin = 8;

    @Column(name = "page_max", nullable = false)
    private int pageMax = 20;

    @Column(name = "page_default", nullable = false)
    private int pageDefault = 12;

    @Column(name = "renderer", nullable = false, length = 32)
    private String renderer = "svg_workspace";

    @Column(name = "theme_template_id", nullable = false, length = 64)
    private String themeTemplateId = "blue-business-text";

    @Column(name = "spec_json", columnDefinition = "LONGTEXT")
    private String specJson;

    @Column(name = "source_pptx_key", nullable = false, length = 512)
    private String sourcePptxKey = "";

    @Column(name = "license_source", nullable = false, length = 256)
    private String licenseSource = "";

    @Column(name = "commercial_ok", nullable = false)
    private boolean commercialOk = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "status", nullable = false)
    private int status = STATUS_DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PptDeckTemplateEntity() {
    }

    public PptDeckTemplateEntity(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category == null || category.isBlank() ? "商务" : category.trim();
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public String getStyleBlurb() {
        return styleBlurb;
    }

    public void setStyleBlurb(String styleBlurb) {
        this.styleBlurb = styleBlurb == null ? "" : styleBlurb;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl == null ? "" : coverUrl;
    }

    public String getAspectRatio() {
        return aspectRatio;
    }

    public void setAspectRatio(String aspectRatio) {
        this.aspectRatio = aspectRatio == null || aspectRatio.isBlank() ? "16:9" : aspectRatio.trim();
    }

    public int getPageMin() {
        return pageMin;
    }

    public void setPageMin(int pageMin) {
        this.pageMin = pageMin;
    }

    public int getPageMax() {
        return pageMax;
    }

    public void setPageMax(int pageMax) {
        this.pageMax = pageMax;
    }

    public int getPageDefault() {
        return pageDefault;
    }

    public void setPageDefault(int pageDefault) {
        this.pageDefault = pageDefault;
    }

    public String getRenderer() {
        return renderer;
    }

    public void setRenderer(String renderer) {
        this.renderer = renderer == null || renderer.isBlank() ? "svg_workspace" : renderer.trim();
    }

    public String getThemeTemplateId() {
        return themeTemplateId;
    }

    public void setThemeTemplateId(String themeTemplateId) {
        this.themeTemplateId = themeTemplateId == null ? "blue-business-text" : themeTemplateId;
    }

    public String getSpecJson() {
        return specJson;
    }

    public void setSpecJson(String specJson) {
        this.specJson = specJson;
    }

    public String getSourcePptxKey() {
        return sourcePptxKey;
    }

    public void setSourcePptxKey(String sourcePptxKey) {
        this.sourcePptxKey = sourcePptxKey == null ? "" : sourcePptxKey;
    }

    public String getLicenseSource() {
        return licenseSource;
    }

    public void setLicenseSource(String licenseSource) {
        this.licenseSource = licenseSource == null ? "" : licenseSource;
    }

    public boolean isCommercialOk() {
        return commercialOk;
    }

    public void setCommercialOk(boolean commercialOk) {
        this.commercialOk = commercialOk;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
