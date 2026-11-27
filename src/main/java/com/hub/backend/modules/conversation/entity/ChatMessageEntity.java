package com.hub.backend.modules.conversation.entity;

import com.hub.backend.infra.llm.dto.LlmUsage;
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
 * One turn of a conversation, either from the user, the model or the platform.
 *
 * <p>{@code sequenceNo} is unique per conversation and gives the client a stable ordering even
 * when two messages share the same timestamp.</p>
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    /** Role of the end user. */
    public static final String ROLE_USER = "user";

    /** Role of the model. */
    public static final String ROLE_ASSISTANT = "assistant";

    /** The turn finished normally. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** The turn failed before a usable answer was produced. */
    public static final String STATUS_FAILED = "FAILED";

    /**
     * The turn was stopped before the model finished. Whatever had already been generated is kept,
     * so the client can show it without pretending the answer is complete.
     */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    /**
     * Groups sibling versions of the same logical turn (edit / regenerate).
     *
     * <p>Assigned to {@link #id} right after the first insert of a new turn. Later versions reuse
     * that id so the client can render a {@code 1/N} pager without a conversation tree.</p>
     */
    @Column(name = "turn_group_id")
    private Long turnGroupId;

    /** 1-based version within {@link #turnGroupId}. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /**
     * Whether this version is the one shown in the timeline and fed back to the model.
     *
     * <p>Inactive siblings are kept for version switching; they are not deleted.</p>
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * For assistant turns: the user message this answer replies to.
     *
     * <p>Lets edit-version switching activate the matching answer without a full branch tree.
     * Null on user rows and on legacy rows that could not be backfilled.</p>
     */
    @Column(name = "reply_to_message_id")
    private Long replyToMessageId;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    // Explicit column definition: MySQL reports LONGTEXT as LONGVARCHAR, which does not match the
    // CLOB type Hibernate derives from @Lob and would fail schema validation.
    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    /**
     * Reasoning trace for this assistant turn, when the model exposed one.
     *
     * <p>Null when the turn had no reasoning (or the client did not ask for it). Kept separate from
     * {@link #content} so reopening a thread can restore the folded thinking panel without mixing
     * it into the answer the user copied or the model will see on the next turn.</p>
     */
    @Column(name = "thinking_content", columnDefinition = "LONGTEXT")
    private String thinkingContent;

    /**
     * JSON array of {@code ChatAttachmentRef}; null/blank means no attachments.
     * Same shape as {@code agent_chat_messages.attachments_json}.
     */
    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    /** Successful file-generation tool arguments, kept server-side for later revision requests. */
    @Column(name = "artifact_context_json", columnDefinition = "LONGTEXT")
    private String artifactContextJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_COMPLETED;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(Long conversationId, Long modelId, int sequenceNo, String role,
                             String content, String status, LlmUsage usage,
                             String errorCode, String errorMessage) {
        this(conversationId, modelId, sequenceNo, role, content, null, null, status, usage,
                errorCode, errorMessage);
    }

    public ChatMessageEntity(Long conversationId, Long modelId, int sequenceNo, String role,
                             String content, String attachmentsJson, String status, LlmUsage usage,
                             String errorCode, String errorMessage) {
        this(conversationId, modelId, sequenceNo, role, content, attachmentsJson, null, status,
                usage, errorCode, errorMessage);
    }

    public ChatMessageEntity(Long conversationId, Long modelId, int sequenceNo, String role,
                             String content, String attachmentsJson, String thinkingContent,
                             String status, LlmUsage usage, String errorCode, String errorMessage) {
        this.conversationId = conversationId;
        this.modelId = modelId;
        this.sequenceNo = sequenceNo;
        this.role = role;
        this.content = content;
        this.attachmentsJson = attachmentsJson;
        this.thinkingContent = blankToNull(thinkingContent);
        this.status = status == null ? STATUS_COMPLETED : status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.version = 1;
        this.active = true;
        if (usage != null) {
            this.promptTokens = usage.promptTokensOrZero();
            this.completionTokens = usage.completionTokensOrZero();
            this.totalTokens = usage.totalTokensOrZero();
        }
    }

    /** Binds this row into an existing turn group as a new version. */
    public void bindVersion(Long turnGroupId, int version) {
        this.turnGroupId = turnGroupId;
        this.version = version;
        this.active = true;
    }

    /** First insert of a turn: the group id equals the row id. */
    public void bindAsOwnTurnGroup() {
        this.turnGroupId = this.id;
        this.version = 1;
        this.active = true;
    }

    /** Marks which user turn this assistant answer belongs to (edit pair switch). */
    public void bindReplyTo(Long userMessageId) {
        this.replyToMessageId = userMessageId;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public void bindArtifactContext(String artifactContextJson) {
        this.artifactContextJson = blankToNull(artifactContextJson);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getModelId() {
        return modelId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public Long getTurnGroupId() {
        return turnGroupId;
    }

    public int getVersion() {
        return version;
    }

    public boolean isActive() {
        return active;
    }

    public Long getReplyToMessageId() {
        return replyToMessageId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getThinkingContent() {
        return thinkingContent;
    }

    public String getAttachmentsJson() {
        return attachmentsJson;
    }

    public String getArtifactContextJson() {
        return artifactContextJson;
    }

    public String getStatus() {
        return status;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
