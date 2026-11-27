package com.hub.backend.modules.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A prompt agent published in the agent catalogue.
 *
 * <p>The agent UI schema is kept as JSON text so the management plane can evolve without adding
 * a column for every new field type. The application validates and serialises it at the service
 * boundary; the database still owns the JSON column.</p>
 */
@Entity
@Table(name = "agents",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_agents_site_key", columnNames = {"site_id", "agent_key"}),
                @UniqueConstraint(name = "uk_agents_sn", columnNames = "sn")
        })
public class AgentEntity {

    public static final String SN_SUPER_AGENT = "super-agent";

    /** 站内 AI 客服挂件专用智能体（可见、已发布；不进超级智能体目录也没关系）。 */
    public static final String SN_SUPPORT_BOT = "support-bot";
    public static final String KEY_SUPPORT_BOT = "support-bot";

    public static final String TYPE_OFFICIAL = "OFFICIAL";
    public static final String TYPE_CUSTOM = "CUSTOM";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_UNPUBLISHED = "UNPUBLISHED";
    public static final String STATUS_DELETED = "DELETED";

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    public static final String PUBLISH_DRAFT = "DRAFT";
    public static final String PUBLISH_PUBLISHED = "PUBLISHED";
    public static final String PUBLISH_DELETED = "DELETED";

    public static final int SCOPE_PRIVATE = 0;
    public static final int SCOPE_PUBLIC = 1;

    public static final int UI_TYPE_COMMON = 1;
    public static final int UI_TYPE_CUSTOM = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "agent_key", nullable = false, length = 128)
    private String agentKey;

    @Column(name = "sn", length = 64)
    private String sn;

    @Column(name = "agent_type", nullable = false, length = 32)
    private String agentType = TYPE_CUSTOM;

    @Column(name = "ui_type", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer uiType = UI_TYPE_COMMON;

    @Column(name = "scope", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer scope = SCOPE_PRIVATE;

    @Column(name = "classify", length = 128)
    private String classify;

    @Column(name = "child_classify", length = 128)
    private String childClassify;

    /**
     * 引用的电商工具（{@code commerce_tools.tool_key}）。
     *
     * <p>非空时，详情页在对话旁边渲染该工具的表单面板，提交走
     * {@code /api/v1/commerce/tools/{toolKey}/compose/stream}。为空就是纯对话形态。</p>
     *
     * <p>存引用而不是把 form_schema 复制一份：运营改工具时所有引用它的智能体自动跟随。</p>
     */
    @Column(name = "linked_tool_key", length = 128)
    private String linkedToolKey;

    @Column(name = "chat_suggest", nullable = false)
    private boolean chatSuggest;

    @Column(name = "ui_bg", length = 1024)
    private String uiBg;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Column(name = "system_prompt", columnDefinition = "LONGTEXT")
    private String systemPrompt;

    @Column(name = "chat_prologue", columnDefinition = "LONGTEXT")
    private String chatPrologue;

    @Column(name = "source_model", length = 64)
    private String sourceModel;

    @Column(name = "source_path", length = 512)
    private String sourcePath;

    @Column(name = "ui_config_json", columnDefinition = "json")
    private String uiConfigJson;

    @Column(name = "visibility", nullable = false, length = 32)
    private String visibility = VISIBILITY_PRIVATE;

    @Column(name = "publish_status", nullable = false, length = 32)
    private String publishStatus = PUBLISH_DRAFT;

    @Column(name = "score", nullable = false, precision = 4, scale = 2)
    private BigDecimal score = new BigDecimal("5.00");

    @Column(name = "see_count", nullable = false)
    private int seeCount;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "favorite_count", nullable = false)
    private int favoriteCount;

    @Column(name = "is_hot", nullable = false)
    private boolean hot;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AgentEntity() {
    }

    public AgentEntity(Long siteId, Long ownerUserId, String agentKey, String sn, String name) {
        this.siteId = siteId;
        this.ownerUserId = ownerUserId;
        this.agentKey = agentKey;
        this.sn = sn;
        this.name = name;
        this.agentType = ownerUserId == null ? TYPE_OFFICIAL : TYPE_CUSTOM;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void softDelete() {
        this.status = STATUS_DELETED;
        this.publishStatus = PUBLISH_DELETED;
        this.visibility = VISIBILITY_PRIVATE;
    }

    public void unpublish() {
        this.status = STATUS_UNPUBLISHED;
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getSn() {
        return sn;
    }

    public String getAgentType() {
        return agentType;
    }

    public Integer getUiType() {
        return uiType;
    }

    public Integer getScope() {
        return scope;
    }

    public String getClassify() {
        return classify;
    }

    public String getChildClassify() {
        return childClassify;
    }

    public boolean isChatSuggest() {
        return chatSuggest;
    }

    public String getUiBg() {
        return uiBg;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getChatPrologue() {
        return chatPrologue;
    }

    public String getSourceModel() {
        return sourceModel;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getUiConfigJson() {
        return uiConfigJson;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getPublishStatus() {
        return publishStatus;
    }

    public BigDecimal getScore() {
        return score;
    }

    public int getSeeCount() {
        return seeCount;
    }

    public int getUseCount() {
        return useCount;
    }

    public int getFavoriteCount() {
        return favoriteCount;
    }

    public boolean isHot() {
        return hot;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public String getStatus() {
        return status;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public void setSn(String sn) {
        this.sn = sn;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public void setUiType(Integer uiType) {
        this.uiType = uiType;
    }

    public void setScope(Integer scope) {
        this.scope = scope;
    }

    public void setClassify(String classify) {
        this.classify = classify;
    }

    public void setChildClassify(String childClassify) {
        this.childClassify = childClassify;
    }

    public String getLinkedToolKey() {
        return linkedToolKey;
    }

    public void setLinkedToolKey(String linkedToolKey) {
        this.linkedToolKey = linkedToolKey;
    }

    public void setChatSuggest(boolean chatSuggest) {
        this.chatSuggest = chatSuggest;
    }

    public void setUiBg(String uiBg) {
        this.uiBg = uiBg;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setChatPrologue(String chatPrologue) {
        this.chatPrologue = chatPrologue;
    }

    public void setSourceModel(String sourceModel) {
        this.sourceModel = sourceModel;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public void setUiConfigJson(String uiConfigJson) {
        this.uiConfigJson = uiConfigJson;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public void setPublishStatus(String publishStatus) {
        this.publishStatus = publishStatus;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public void setSeeCount(int seeCount) {
        this.seeCount = seeCount;
    }

    public void setUseCount(int useCount) {
        this.useCount = useCount;
    }

    public void setFavoriteCount(int favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    public void setHot(boolean hot) {
        this.hot = hot;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
