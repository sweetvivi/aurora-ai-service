package com.hub.backend.modules.model.entity;

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
import java.util.ArrayList;
import java.util.List;

/**
 * A model published in the catalogue.
 *
 * <p>Only the columns required by the chat path are mapped. Pricing and capability flags stay in
 * the database and are mapped by the model catalogue module when it lands.</p>
 *
 * <p>{@code route} is the value actually sent to the gateway, so a catalogue entry can expose a
 * friendly key while still calling a provider specific model name.</p>
 */
@Entity
@Table(name = "llm_models",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_llm_models_source_external",
                columnNames = {"source", "external_id"}))
public class LlmModelEntity {

    public static final String SOURCE_LOCAL = "LOCAL";

    public static final String SOURCE_NEW_API = "NEW_API";

    /** Status of a model that is published in the catalogue. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Status of a model hidden from the catalogue; the row itself is kept. */
    public static final String STATUS_DISABLED = "DISABLED";

    /** A conversational model, served by the chat endpoints. */
    public static final String TYPE_CHAT = "CHAT";

    /** An image model, served by {@code POST /api/v1/images/generations}. */
    public static final String TYPE_IMAGE = "IMAGE";

    /** A video model. Reserved: no endpoint serves this type yet. */
    public static final String TYPE_VIDEO = "VIDEO";

    /** An audio model. Reserved: no endpoint serves this type yet. */
    public static final String TYPE_AUDIO = "AUDIO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 32)
    private String source = SOURCE_LOCAL;

    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(name = "site_id")
    private Long siteId;

    /** Supplier that serves this model, {@code null} when the service defaults apply. */
    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "group_id")
    private Long groupId;

    /** Base model this row is a variant of, {@code null} when it stands on its own. */
    @Column(name = "parent_model_id")
    private Long parentModelId;

    @Column(name = "model_key", nullable = false, length = 128)
    private String modelKey;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    /**
     * What the model produces: {@link #TYPE_CHAT}, {@link #TYPE_IMAGE}, {@link #TYPE_VIDEO} or
     * {@link #TYPE_AUDIO}.
     */
    @Column(name = "model_type", nullable = false, length = 32)
    private String modelType = TYPE_CHAT;

    @Column(name = "company", length = 128)
    private String company;

    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Column(name = "description", length = 1024)
    private String description;

    /**
     * Per-model capability declaration as JSON, for example which video modes a model accepts.
     *
     * <p>Kept as text rather than mapped to an object because the shape belongs to the consumer:
     * the catalogue returns it verbatim and the console edits it. {@code null} means "no
     * declaration" and clients fall back to their own defaults.</p>
     */
    /**
     * Stored as text in the JPA mapping so H2 tests can round-trip the document; production MySQL
     * keeps the real {@code JSON} column via Flyway and still accepts string binds.
     */
    @Column(name = "capabilities_json")
    private String capabilitiesJson;

    /**
     * Context window in tokens, {@code null} when nobody recorded it.
     *
     * <p>{@code null} and {@code 0} are deliberately different: the console shows a blank for "not
     * stated" instead of claiming the model accepts no context at all.</p>
     */
    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "supports_vision", nullable = false)
    private boolean supportsVision;

    @Column(name = "supports_tools", nullable = false)
    private boolean supportsTools;

    /**
     * Whether the upstream model accepts the built-in {@code web_search} tool
     * ({@code tools:[{type:"web_search"}]}). Local gate — not sourced from New API sync.
     */
    @Column(name = "supports_web_search", nullable = false)
    private boolean supportsWebSearch;

    /** Points charged per 1K input tokens; {@code null} when pricing has not been split by direction. */
    @Column(name = "input_points_per_1k", precision = 20, scale = 6)
    private BigDecimal inputPointsPer1k;

    @Column(name = "output_points_per_1k", precision = 20, scale = 6)
    private BigDecimal outputPointsPer1k;

    @Column(name = "route", length = 255)
    private String route;

    @Column(name = "is_free", nullable = false)
    private boolean free;

    @Column(name = "is_thinking", nullable = false)
    private boolean thinking;

    @Column(name = "only_points", nullable = false)
    private boolean pointsOnly;

    @Column(name = "allow_points", nullable = false)
    private boolean pointsAllowed = true;

    @Column(name = "consume_points", nullable = false, precision = 20, scale = 6)
    private BigDecimal consumePoints = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "upstream_available", nullable = false)
    private boolean upstreamAvailable = true;

    /**
     * 该模型在 New API 里可用的分组，逗号分隔（来自广场的 {@code enable_groups}）。
     *
     * <p>广场对「模型行」按调用者分组过滤，但不会裁剪这一份数组，所以一次同步就能拿到每个
     * 模型的全部分组。中继按这里选令牌的分组；为空表示未登记，回落到 {@code app.new-api.group}，
     * 与升级前的行为一致。</p>
     */
    @Column(name = "upstream_groups", length = 512)
    private String upstreamGroups;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmModelEntity() {
    }

    public LlmModelEntity(String modelKey, String modelName, String company, String route) {
        this.modelKey = modelKey;
        this.modelName = modelName;
        this.company = company;
        this.route = route;
    }

    /**
     * @return the model key that must be sent upstream, falling back to the catalogue key
     */
    public String resolvedRoute() {
        return route == null || route.isBlank() ? modelKey : route;
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Long getSiteId() {
        return siteId;
    }

    public Long getProviderId() {
        return providerId;
    }

    public Long getParentModelId() {
        return parentModelId;
    }

    public void setParentModelId(Long parentModelId) {
        this.parentModelId = parentModelId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    /** @return never {@code null}; defaults to {@link #TYPE_CHAT} */
    public String getModelType() {
        return modelType == null ? TYPE_CHAT : modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public void setConsumePoints(java.math.BigDecimal consumePoints) {
        this.consumePoints = consumePoints == null ? java.math.BigDecimal.ZERO : consumePoints;
    }

    public Long getGroupId() {
        return groupId;
    }

    public String getModelKey() {
        return modelKey;
    }

    public String getModelName() {
        return modelName;
    }

    public String getCompany() {
        return company;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public String getDescription() {
        return description;
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public void setCapabilitiesJson(String capabilitiesJson) {
        this.capabilitiesJson = capabilitiesJson;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public boolean isSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(boolean supportsVision) {
        this.supportsVision = supportsVision;
    }

    public boolean isSupportsTools() {
        return supportsTools;
    }

    public void setSupportsTools(boolean supportsTools) {
        this.supportsTools = supportsTools;
    }

    public boolean isSupportsWebSearch() {
        return supportsWebSearch;
    }

    public void setSupportsWebSearch(boolean supportsWebSearch) {
        this.supportsWebSearch = supportsWebSearch;
    }

    public BigDecimal getInputPointsPer1k() {
        return inputPointsPer1k;
    }

    public void setInputPointsPer1k(BigDecimal inputPointsPer1k) {
        this.inputPointsPer1k = inputPointsPer1k;
    }

    public BigDecimal getOutputPointsPer1k() {
        return outputPointsPer1k;
    }

    public void setOutputPointsPer1k(BigDecimal outputPointsPer1k) {
        this.outputPointsPer1k = outputPointsPer1k;
    }

    /** @return whether the model is free to use */
    public boolean isFree() {
        return free;
    }

    public boolean isThinking() {
        return thinking;
    }

    public boolean isPointsOnly() {
        return pointsOnly;
    }

    public boolean isPointsAllowed() {
        return pointsAllowed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setFree(boolean free) {
        this.free = free;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }

    public void setPointsOnly(boolean pointsOnly) {
        this.pointsOnly = pointsOnly;
    }

    public void setPointsAllowed(boolean pointsAllowed) {
        this.pointsAllowed = pointsAllowed;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRoute() {
        return route;
    }

    public BigDecimal getConsumePoints() {
        return consumePoints;
    }

    public String getStatus() {
        return status;
    }

    public boolean isUpstreamAvailable() {
        return upstreamAvailable;
    }

    public void setUpstreamAvailable(boolean upstreamAvailable) {
        this.upstreamAvailable = upstreamAvailable;
    }

    public String getUpstreamGroups() {
        return upstreamGroups;
    }

    public void setUpstreamGroups(String upstreamGroups) {
        this.upstreamGroups = upstreamGroups;
    }

    /** 解析成列表；未登记时返回空列表。 */
    public List<String> upstreamGroupList() {
        if (upstreamGroups == null || upstreamGroups.isBlank()) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        for (String part : upstreamGroups.split(",")) {
            String group = part.strip();
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        return List.copyOf(groups);
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** Publishes or retires the model from the catalogue. */
    public void setStatus(String status) {
        this.status = status;
    }
}
