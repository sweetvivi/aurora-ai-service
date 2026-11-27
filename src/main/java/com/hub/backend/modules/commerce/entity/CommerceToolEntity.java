package com.hub.backend.modules.commerce.entity;

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
 * 一个电商工具（商品换背景、模特试衣等）。
 *
 * <p>工具 schema 与生成能力仍独立于 {@code agents}。超级智能体目录中的电商条目只保存
 * 展示信息和 {@code linked_tool_key}，运行时继续回到本表读取唯一的表单定义，避免后台
 * 同时维护两份电商业务配置。</p>
 *
 * <p>{@code upload_roles} / {@code retouch_modes} 存 JSON 字符串而不是 MySQL 的
 * {@code JSON} 类型：Hibernate 的 ddl-auto=validate 在 MySQL 与测试用的 H2 之间
 * 无法可靠校验 JSON 列（同样的取舍见 {@code UserIdentityEntity}）。</p>
 */
@Entity
@Table(name = "commerce_tools")
public class CommerceToolEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 管理端「删除」为软删除：行仍在库里，只是不再下发给前台 */
    public static final String STATUS_DELETED = "DELETED";

    /** 电商工具：`/e-commerce` 工作台与超级智能体引用它们 */
    public static final String SCENE_COMMERCE = "commerce";

    /** 绘画工具：`/painting` 的「图片创作」工具库 */
    public static final String SCENE_PAINTING = "painting";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id")
    private Long siteId;

    /**
     * 工具归属的场景：{@link #SCENE_COMMERCE} 或 {@link #SCENE_PAINTING}。
     *
     * <p>为什么不复用 {@code category} 区分：category 已经是分类（商品替换 / 抠图去背 …），
     * 是场景下的二级概念。混用会让目录分组与「只列本场景工具」这类过滤都没法写。</p>
     */
    @Column(name = "scene", nullable = false, length = 32)
    private String scene = SCENE_COMMERCE;

    @Column(name = "tool_key", nullable = false, length = 128)
    private String toolKey;

    @Column(name = "category", nullable = false, length = 128)
    private String category;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "prompt", columnDefinition = "LONGTEXT")
    private String prompt;

    @Column(name = "form", nullable = false, length = 32)
    private String form;

    @Column(name = "glyph", length = 32)
    private String glyph;

    /**
     * 自定义图标图片；非空时优先于 {@link #glyph}。
     *
     * <p>两列并存而不是塞进 glyph：glyph 是「单字或图标类名」，靠前缀猜类型会让校验
     * 与清理变模糊。前台渲染顺序固定为 icon_url → glyph。</p>
     */
    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Column(name = "accent", length = 16)
    private String accent;

    @Column(name = "upload_roles", length = 2048)
    private String uploadRoles;

    @Column(name = "retouch_modes", length = 512)
    private String retouchModes;

    /**
     * 每个工具自己的表单定义（JSON 字符串）：上传槽、选项、下拉、右栏说明、示例图。
     *
     * <p>为空表示这个工具还没配，前端退回按 {@code form} 推导上传槽的旧渲染。</p>
     */
    @Column(name = "form_schema", length = 4000)
    private String formSchema;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CommerceToolEntity() {
    }

    /**
     * 管理端新建工具用。
     *
     * <p>无参构造刻意保持 protected（只有 JPA 该用），跨包创建走这个工厂，
     * 免得为了 new 一行而把构造函数的可见性放开。</p>
     */
    public static CommerceToolEntity newTool() {
        return new CommerceToolEntity();
    }

    public Long getId() {
        return id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public String getScene() {
        return scene;
    }

    public String getToolKey() {
        return toolKey;
    }

    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getForm() {
        return form;
    }

    public String getGlyph() {
        return glyph;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public String getAccent() {
        return accent;
    }

    public String getUploadRoles() {
        return uploadRoles;
    }

    public String getRetouchModes() {
        return retouchModes;
    }

    public String getFormSchema() {
        return formSchema;
    }

    public int getSortOrder() {
        return sortOrder;
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

    /* ------------------------------ 写入侧 ------------------------------ */

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public void setToolKey(String toolKey) {
        this.toolKey = toolKey;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public void setForm(String form) {
        this.form = form;
    }

    public void setGlyph(String glyph) {
        this.glyph = glyph;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public void setAccent(String accent) {
        this.accent = accent;
    }

    public void setUploadRoles(String uploadRoles) {
        this.uploadRoles = uploadRoles;
    }

    public void setRetouchModes(String retouchModes) {
        this.retouchModes = retouchModes;
    }

    public void setFormSchema(String formSchema) {
        this.formSchema = formSchema;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
