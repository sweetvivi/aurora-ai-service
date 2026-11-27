package com.hub.backend.modules.conversation.entity;

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
 * A conversation thread owned by one account.
 */
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    /** A plain one-to-one conversation with a single model. */
    public static final String TYPE_CHAT = "CHAT";

    /**
     * A conversation owned by the drawing page: every generated image gets its own thread, whose
     * title carries the prompt. It shares the table with chat, so the type is what keeps the two
     * histories apart — chat lists {@link #TYPE_CHAT} only.
     */
    public static final String TYPE_PAINTING = "PAINTING";

    /**
     * A conversation owned by the (retired) video workbench: one thread per generated clip, titled
     * {@code AI视频：{prompt}}.
     *
     * <p>The current video page keeps its history in {@code video_tasks}; these threads are legacy
     * rows. They are labelled, not deleted, so the attachments stay reachable — but because they
     * are no longer {@code CHAT}, they stop showing up in the chat history.</p>
     */
    public static final String TYPE_VIDEO = "VIDEO";

    /** Status of a conversation that is visible to its owner. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Status of a conversation removed by its owner. */
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "model_id")
    private Long modelId;

    /**
     * Optional IMAGE-model preference for Doubao-style chat tools ({@code generate_image} /
     * {@code edit_image}). Null means "use the site default image model". Never store a CHAT
     * model here — that stays in {@link #modelId}.
     */
    @Column(name = "image_model_id")
    private Long imageModelId;

    /** 会话列表只显示一行，列宽也是 255。 */
    public static final int TITLE_MAX_LENGTH = 255;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "conversation_type", nullable = false, length = 32)
    private String conversationType = TYPE_CHAT;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    protected ConversationEntity() {
    }

    public ConversationEntity(Long userId, Long modelId, String title) {
        this(userId, modelId, title, TYPE_CHAT);
    }

    public ConversationEntity(Long userId, Long modelId, String title, String conversationType) {
        this.userId = userId;
        this.modelId = modelId;
        this.title = normalizeTitle(title);
        this.conversationType = conversationType == null || conversationType.isBlank()
                ? TYPE_CHAT
                : conversationType;
        this.status = STATUS_ACTIVE;
        this.messageCount = 0;
    }

    /**
     * 收敛会话标题：去首尾空白，超长一律截断而不是报错。
     *
     * <p>标题通常直接取用户的第一条消息，一条长 prompt 上千字很常见；如果让它原样进库，
     * 要么触发字段校验把整轮对话打回 400（用户只看到「请求参数有误」），要么被 MySQL
     * 静默截断或直接抛数据异常。标题只是列表里的一行字，截断没有信息损失，也不该
     * 让一次正常的生成失败。</p>
     */
    public static String normalizeTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= TITLE_MAX_LENGTH) {
            return text;
        }
        int end = TITLE_MAX_LENGTH;
        // 别把 emoji 这类代理对劈成半个字符，截完会变成乱码方块
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** Stores a derived title only when the conversation does not have one yet. */
    public boolean applyTitleIfBlank(String candidate) {
        String normalized = normalizeTitle(candidate);
        if (normalized == null) {
            return false;
        }
        if (title != null && !title.isBlank()) {
            return false;
        }
        this.title = normalized;
        return true;
    }

    public void registerMessages(int added) {
        this.messageCount = Math.max(0, this.messageCount + added);
    }

    /** Renames the thread. Titles are free text chosen by the owner, so they are truncated too. */
    public void rename(String title) {
        this.title = normalizeTitle(title);
    }

    /** Marks the thread as removed without destroying its messages. */
    public void softDelete(LocalDateTime at) {
        this.status = STATUS_DELETED;
        this.deletedAt = at == null ? LocalDateTime.now() : at;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getModelId() {
        return modelId;
    }

    public Long getImageModelId() {
        return imageModelId;
    }

    /** Remembers the IMAGE model the user picked in chat creative settings. */
    public void setImageModelId(Long imageModelId) {
        this.imageModelId = imageModelId;
    }

    public String getTitle() {
        return title;
    }

    public String getConversationType() {
        return conversationType;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setTitle(String title) {
        this.title = normalizeTitle(title);
    }
}
