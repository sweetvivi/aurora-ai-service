package com.hub.backend.modules.agent.entity;

import com.hub.backend.infra.llm.dto.LlmUsage;
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

@Entity
@Table(name = "agent_chat_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_chat_messages_sequence",
                columnNames = {"agent_conversation_id", "sequence_no"}))
public class AgentChatMessageEntity {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_conversation_id", nullable = false)
    private Long agentConversationId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    /**
     * 同一条消息的版本组：编辑或重新生成时，新版本进同一个组。
     *
     * <p>首次插入的新行取自身 id（见 {@link #bindAsOwnTurnGroup()}），与 {@code chat_messages}
     * 的 V62 语义一致 —— 这样客户端按组渲染 {@code 1/N} 翻页即可，不需要对话树。</p>
     */
    @Column(name = "turn_group_id")
    private Long turnGroupId;

    /** 组内版本号，从 1 开始。 */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /**
     * 是否是时间线上显示、并且会被喂回模型的版本。
     *
     * <p>非活跃的兄弟版本保留着供切换，不删除。重建上下文时必须只取 active，否则被编辑掉的
     * 旧问题会连同答案一起进模型输入 —— 那正是「编辑」这个动作要消除的东西。</p>
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * assistant 行回答的是哪条 user 消息。
     *
     * <p>切换 user 的编辑版本时靠它同步激活配对的答案，不必引入完整的对话分支树。
     * user 行与早期未能回填的行上为 null。</p>
     */
    @Column(name = "reply_to_message_id")
    private Long replyToMessageId;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "thinking_content", columnDefinition = "LONGTEXT")
    private String thinkingContent;

    /**
     * 本轮发生过的工具调用，JSON 数组；为空表示没调用过工具。
     *
     * <p>只给界面还原卡片用，<b>不进模型上下文</b> —— 上下文只需要正文。</p>
     */
    @Column(name = "tool_calls_json", columnDefinition = "LONGTEXT")
    private String toolCallsJson;

    /**
     * 随消息送进模型的图片引用，JSON 数组；为空表示这条消息没有附件。
     *
     * <p>只存指针（fileId + mime + 原始名），二进制在 {@code uploaded_files} 与磁盘上。
     * 重建上下文时按 id 回读，文件已删除就跳过这张图。</p>
     */
    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_COMPLETED;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

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

    protected AgentChatMessageEntity() {
    }

    public AgentChatMessageEntity(Long agentConversationId, Long modelId, int sequenceNo, String role,
                                  String content, String thinkingContent, String toolCallsJson,
                                  String attachmentsJson, String status, LlmUsage usage,
                                  String providerMessageId, String errorCode, String errorMessage) {
        this.agentConversationId = agentConversationId;
        this.modelId = modelId;
        this.sequenceNo = sequenceNo;
        this.role = role;
        this.content = content;
        this.thinkingContent = thinkingContent;
        this.toolCallsJson = toolCallsJson;
        this.attachmentsJson = attachmentsJson;
        this.status = status == null ? STATUS_COMPLETED : status;
        this.providerMessageId = providerMessageId;
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

    /** 把这一行作为新版本绑进已有的版本组。 */
    public void bindVersion(Long turnGroupId, int version) {
        this.turnGroupId = turnGroupId;
        this.version = version;
        this.active = true;
    }

    /** 一轮里的第一次插入：组 id 等于自身 id。 */
    public void bindAsOwnTurnGroup() {
        this.turnGroupId = this.id;
        this.version = 1;
        this.active = true;
    }

    /** 标明这条 assistant 答案回答的是哪条 user 消息（编辑配对切换用）。 */
    public void bindReplyTo(Long userMessageId) {
        this.replyToMessageId = userMessageId;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public boolean isActive() {
        return active;
    }

    public Long getTurnGroupId() {
        return turnGroupId;
    }

    public int getVersion() {
        return version;
    }

    public Long getReplyToMessageId() {
        return replyToMessageId;
    }

    public Long getId() {
        return id;
    }

    public Long getAgentConversationId() {
        return agentConversationId;
    }

    public Long getModelId() {
        return modelId;
    }

    public int getSequenceNo() {
        return sequenceNo;
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

    public String getToolCallsJson() {
        return toolCallsJson;
    }

    public String getAttachmentsJson() {
        return attachmentsJson;
    }

    public String getStatus() {
        return status;
    }

    public String getProviderMessageId() {
        return providerMessageId;
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
