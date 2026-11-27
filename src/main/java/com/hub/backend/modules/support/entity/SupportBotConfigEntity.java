package com.hub.backend.modules.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_bot_config")
public class SupportBotConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "agent_sn", nullable = false, length = 64)
    private String agentSn = "support-bot";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "online_mode", nullable = false, length = 32)
    private String onlineMode = "ALWAYS";

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(name = "schedule_json", columnDefinition = "TEXT")
    private String scheduleJson;

    @Column(name = "welcome_title", nullable = false, length = 255)
    private String welcomeTitle = "欢迎";

    @Column(name = "welcome_description", nullable = false, length = 1024)
    private String welcomeDescription = "有问题随时问我，也可以转人工处理。";

    @Column(name = "home_title", nullable = false, length = 255)
    private String homeTitle = "AI 助手";

    @Column(name = "brand_name", nullable = false, length = 128)
    private String brandName = "AI 客服";

    @Column(name = "brand_color", nullable = false, length = 32)
    private String brandColor = "#16a34a";

    /**
     * 客服上游 OpenAI 兼容基地址（如 {@code https://api.example.com/v1}）。管理端可读写。
     */
    @Column(name = "platform_base_url", length = 512)
    private String platformBaseUrl;

    /**
     * 客服对话使用的平台上游密钥（费用记平台，不扣用户）。管理端可写；接口只回传是否已配置。
     */
    @Column(name = "platform_api_key", columnDefinition = "TEXT")
    private String platformApiKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getAgentSn() {
        return agentSn;
    }

    public void setAgentSn(String agentSn) {
        this.agentSn = agentSn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOnlineMode() {
        return onlineMode;
    }

    public void setOnlineMode(String onlineMode) {
        this.onlineMode = onlineMode;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getScheduleJson() {
        return scheduleJson;
    }

    public void setScheduleJson(String scheduleJson) {
        this.scheduleJson = scheduleJson;
    }

    public String getWelcomeTitle() {
        return welcomeTitle;
    }

    public void setWelcomeTitle(String welcomeTitle) {
        this.welcomeTitle = welcomeTitle;
    }

    public String getWelcomeDescription() {
        return welcomeDescription;
    }

    public void setWelcomeDescription(String welcomeDescription) {
        this.welcomeDescription = welcomeDescription;
    }

    public String getHomeTitle() {
        return homeTitle;
    }

    public void setHomeTitle(String homeTitle) {
        this.homeTitle = homeTitle;
    }

    public String getBrandName() {
        return brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public String getBrandColor() {
        return brandColor;
    }

    public void setBrandColor(String brandColor) {
        this.brandColor = brandColor;
    }

    public String getPlatformBaseUrl() {
        return platformBaseUrl;
    }

    public void setPlatformBaseUrl(String platformBaseUrl) {
        this.platformBaseUrl = platformBaseUrl;
    }

    public String getPlatformApiKey() {
        return platformApiKey;
    }

    public void setPlatformApiKey(String platformApiKey) {
        this.platformApiKey = platformApiKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
