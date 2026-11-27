package com.hub.backend.modules.model.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Configuration of one upstream model supplier.
 *
 * <p>Lives in the model module rather than in the admin module: this is domain configuration that
 * the chat and image paths read at request time, while {@code modules/admin} only exposes the HTTP
 * surface that edits it. Putting the row next to the admin controller would have made the core
 * request path depend on a management-UI module.</p>
 */
@Entity
@Table(name = "llm_providers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_llm_providers_source_external",
                columnNames = {"source", "external_id"}))
public class LlmProviderEntity {

    public static final String SOURCE_LOCAL = "LOCAL";

    public static final String SOURCE_NEW_API = "NEW_API";

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_DISABLED = "DISABLED";

    /**
     * What the console groups this supplier under.
     *
     * <p>Descriptive, not enforced. One gateway can legitimately serve several model types, and the
     * rule that actually decides which endpoint may run a model is {@code llm_models.model_type},
     * checked on every call. A category is what makes "语言供应商 / 绘画供应商" two tabs in the
     * console instead of a field the front end has to dig out of {@code metadataJson}.</p>
     */
    public static final String CATEGORY_CHAT = "CHAT";

    public static final String CATEGORY_IMAGE = "IMAGE";

    public static final String CATEGORY_VIDEO = "VIDEO";

    public static final String CATEGORY_AUDIO = "AUDIO";

    public static final String CATEGORY_OTHER = "OTHER";

    /** Category of a supplier whose purpose was not stated. */
    public static final String DEFAULT_CATEGORY = CATEGORY_CHAT;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 32)
    private String source = SOURCE_LOCAL;

    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(name = "provider_key", nullable = false, length = 128)
    private String providerKey;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "category", nullable = false, length = 32)
    private String category = DEFAULT_CATEGORY;

    @Column(name = "protocol", nullable = false, length = 64)
    private String protocol;

    @Column(name = "base_url", nullable = false, length = 1024)
    private String baseUrl;

    /**
     * Origin a finished video is downloaded from, when it differs from {@link #baseUrl}.
     *
     * <p>Task plugins answer the status call with a host-less path such as
     * {@code /v1/videos/{id}/content}. That path belongs to the supplier that produced the file, so
     * a caller submitting through a relay needs to know where the bytes actually live.
     * {@code null} means "no separate origin": the path is resolved against the submit base, which
     * is the relay, and a relay that cannot proxy artifact content simply fails to deliver the
     * file — exactly the behaviour before this column existed.</p>
     */
    @Column(name = "video_download_base_url", length = 1024)
    private String videoDownloadBaseUrl;

    /**
     * Key that can fetch bytes from {@link #videoDownloadBaseUrl}, when it differs from the submit
     * key.
     *
     * <p>A dedicated column rather than reuse of {@link #apiKey} for a reason that is not
     * cosmetic: the catalogue sync rewrites the relay-owned supplier row on every refresh and
     * clears {@code api_key} by design, so a key stored there survives at most one sync interval
     * and then silently falls back to the relay key — which the supplier origin rejects. This
     * column is deliberately absent from that reset list, so it behaves like the origin beside it:
     * operator data the sync leaves alone.</p>
     *
     * <p>{@code null} keeps the previous resolution order — the row's own key, then
     * {@code app.llm.video-download-api-key} — so a deployment that configured the environment
     * variable keeps working unchanged.</p>
     */
    @Column(name = "video_download_api_key", columnDefinition = "TEXT")
    private String videoDownloadApiKey;

    @Column(name = "endpoint_path", length = 255)
    private String endpointPath;

    /**
     * Relative image path of this supplier, e.g. {@code /images/generations}.
     *
     * <p>{@code null} means "use {@code app.llm.images-path}", so a supplier that sits on the
     * gateway's default needs nothing here and existing rows keep their exact previous behaviour.</p>
     */
    @Column(name = "images_path", length = 255)
    private String imagesPath;

    @Column(name = "api_version", length = 64)
    private String apiVersion;

    @Column(name = "auth_type", nullable = false, length = 64)
    private String authType;

    /** Reference such as {@code env:OPENAI_API_KEY}; used only when {@link #apiKey} is empty. */
    @Column(name = "credential_ref", nullable = false, length = 255)
    private String credentialRef;

    /**
     * The key itself, as typed by an operator.
     *
     * <p>Stored in clear text by an explicit product decision: the admin console has to be able to
     * read a supplier back and edit it, and the operators did not want a second secret store. The
     * consequence is that this column is only as protected as the database — see
     * {@code docs/provider-admin-api.md} §5.</p>
     *
     * <p>Explicit column definition because MySQL reports {@code TEXT} as LONGVARCHAR, which does not
     * match what Hibernate derives for a plain {@code String} under schema validation.</p>
     */
    @Column(name = "api_key", columnDefinition = "TEXT")
    private String apiKey;

    @Column(name = "credential_fingerprint", columnDefinition = "CHAR(64)")
    private String credentialFingerprint;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "upstream_available", nullable = false)
    private boolean upstreamAvailable = true;

    @Column(name = "provider_group", length = 255)
    private String providerGroup;

    @Column(name = "priority", nullable = false)
    private long priority;

    @Column(name = "weight", nullable = false)
    private int weight;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LlmProviderEntity() {
    }

    public LlmProviderEntity(String providerKey, String name, String protocol, String baseUrl, String endpointPath,
                             String apiVersion, String authType, String credentialRef, String status, int sortOrder) {
        this.providerKey = providerKey;
        this.name = name;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.endpointPath = endpointPath;
        this.apiVersion = apiVersion;
        this.authType = authType;
        this.credentialRef = credentialRef;
        this.status = status;
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /** @return whether an operator has supplied a key that the request path can use */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
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

    public String getProviderKey() {
        return providerKey;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category == null ? DEFAULT_CATEGORY : category;
    }

    public void setCategory(String category) {
        this.category = category == null || category.isBlank() ? DEFAULT_CATEGORY : category.strip();
    }

    public String getImagesPath() {
        return imagesPath;
    }

    public void setImagesPath(String imagesPath) {
        this.imagesPath = imagesPath == null || imagesPath.isBlank() ? null : imagesPath.strip();
    }

    public String getProtocol() {
        return protocol;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getVideoDownloadBaseUrl() {
        return videoDownloadBaseUrl;
    }

    /** @param value {@code null} or blank clears the override and restores the relay behaviour */
    public void setVideoDownloadBaseUrl(String value) {
        this.videoDownloadBaseUrl = value == null || value.isBlank() ? null : value.strip();
    }

    public String getVideoDownloadApiKey() {
        return videoDownloadApiKey;
    }

    /** @param value {@code null} or blank clears the key and restores the configured fallback */
    public void setVideoDownloadApiKey(String value) {
        this.videoDownloadApiKey = value == null || value.isBlank() ? null : value.strip();
    }

    /** @return whether this row carries a download key of its own */
    public boolean hasVideoDownloadApiKey() {
        return videoDownloadApiKey != null && !videoDownloadApiKey.isBlank();
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getAuthType() {
        return authType;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getCredentialFingerprint() {
        return credentialFingerprint;
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

    public String getProviderGroup() {
        return providerGroup;
    }

    public void setProviderGroup(String providerGroup) {
        this.providerGroup = providerGroup;
    }

    public long getPriority() {
        return priority;
    }

    public void setPriority(long priority) {
        this.priority = priority;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setCredentialFingerprint(String credentialFingerprint) {
        this.credentialFingerprint = credentialFingerprint;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }
}
