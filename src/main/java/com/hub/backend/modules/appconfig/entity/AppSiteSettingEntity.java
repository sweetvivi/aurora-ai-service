package com.hub.backend.modules.appconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One runtime setting of a white label site.
 *
 * <p>Keys are namespaced by convention: {@code feature.*} carries feature switches, everything
 * else is a flat site property such as {@code announcement} or {@code default_model}.</p>
 */
@Entity
@Table(name = "app_site_settings")
public class AppSiteSettingEntity {

    /** Key prefix of the feature switch namespace. */
    public static final String FEATURE_PREFIX = "feature.";

    /** Announcement shown on the client home page. */
    public static final String KEY_ANNOUNCEMENT = "announcement";

    /** Model key used when a client does not pick one. */
    public static final String KEY_DEFAULT_MODEL = "default_model";

    /** Optional illustration displayed beside the AI education home hero. */
    public static final String KEY_AI_EDUCATION_HERO_IMAGE = "ai_education.hero_image_url";

    /** Site chrome logos — empty means fall back to the next layer (slot → default → built-in). */
    public static final String KEY_BRANDING_LOGO_DEFAULT = "branding.logo.default";
    public static final String KEY_BRANDING_LOGO_AUTH = "branding.logo.auth";
    public static final String KEY_BRANDING_LOGO_SIDENAV = "branding.logo.sidenav";
    public static final String KEY_BRANDING_LOGO_CHAT = "branding.logo.chat";
    public static final String KEY_BRANDING_LOGO_ASSISTANT = "branding.logo.assistant";
    public static final String KEY_BRANDING_LOGO_TOAST = "branding.logo.toast";

    /** Value is a boolean literal. */
    public static final String TYPE_BOOLEAN = "BOOLEAN";

    /** Value is free text. */
    public static final String TYPE_STRING = "STRING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "setting_key", nullable = false, length = 128)
    private String settingKey;

    @Column(name = "setting_value", length = 2048)
    private String settingValue;

    @Column(name = "value_type", nullable = false, length = 16)
    private String valueType = TYPE_STRING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppSiteSettingEntity() {
    }

    public AppSiteSettingEntity(Long siteId, String settingKey, String settingValue, String valueType) {
        this.siteId = siteId;
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.valueType = valueType == null ? TYPE_STRING : valueType;
    }

    /**
     * @param fallback value returned when the stored value is absent or not a boolean literal
     */
    public boolean booleanValue(boolean fallback) {
        if (settingValue == null || settingValue.isBlank()) {
            return fallback;
        }
        String normalized = settingValue.strip();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return fallback;
    }

    /** @return the feature name when the key is inside the feature namespace, otherwise {@code null} */
    public String featureName() {
        if (settingKey == null || !settingKey.startsWith(FEATURE_PREFIX)) {
            return null;
        }
        String name = settingKey.substring(FEATURE_PREFIX.length());
        return name.isEmpty() ? null : name;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public String getValueType() {
        return valueType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }
}
