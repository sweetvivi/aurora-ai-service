package com.hub.backend.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Management-plane payload for creating and updating an agent.
 *
 * <p>Null fields keep their current values on update. That is intentional: the console can patch a
 * status or ordering value without first reading and echoing the full prompt and relation set.</p>
 */
@Schema(name = "AgentAdminRequest", description = "后台创建或更新智能体")
public record AgentAdminRequest(
        @Schema(description = "所属站点 ID；null 表示平台全局")
        Long siteId,

        @Schema(description = "归属用户 ID；null 表示平台官方智能体")
        Long ownerUserId,

        @Schema(description = "智能体类型：OFFICIAL / CUSTOM", example = "OFFICIAL")
        String agentType,

        @Schema(description = "状态：ACTIVE / UNPUBLISHED / DELETED", example = "ACTIVE")
        String status,

        @Schema(description = "后台排序号", example = "10")
        @Min(value = 0, message = "sortOrder must not be negative")
        Integer sortOrder,

        @Schema(description = "是否热门")
        Boolean hot,

        @Schema(description = "浏览量")
        @Min(value = 0, message = "seeCount must not be negative")
        Integer seeCount,

        @Schema(description = "使用次数")
        @Min(value = 0, message = "useCount must not be negative")
        Integer useCount,

        @Schema(description = "智能体名称", example = "Python专家")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Schema(description = "智能体简介", example = "帮助编写、调试和优化 Python 代码")
        @Size(max = 1024, message = "description must be at most 1024 characters")
        String description,

        @Schema(description = "头像地址")
        @Size(max = 1024, message = "iconUrl must be at most 1024 characters")
        String iconUrl,

        @Schema(description = "系统提示词，对话时作为第一条 system 消息发送")
        @Size(max = 200000, message = "systemPrompt must be at most 200000 characters")
        String systemPrompt,

        @Schema(description = "聊天开场白")
        @Size(max = 200000, message = "chatPrologue must be at most 200000 characters")
        String chatPrologue,

        @Schema(description = "导入来源模型；仅用于运营追溯")
        @Size(max = 64, message = "sourceModel must be at most 64 characters")
        String sourceModel,

        @Schema(description = "导入来源路径；仅用于运营追溯")
        @Size(max = 512, message = "sourcePath must be at most 512 characters")
        String sourcePath,

        @Schema(description = "分类 ID", example = "1")
        Long categoryId,

        @Schema(description = "前端渲染类型：1 通用，2 自定义", example = "1")
        @Min(value = 1, message = "uiType must be 1 or 2")
        @Max(value = 2, message = "uiType must be 1 or 2")
        Integer uiType,

        @Schema(description = "展示范围：0 私有，1 公开", example = "1")
        @Min(value = 0, message = "scope must be 0 or 1")
        @Max(value = 1, message = "scope must be 0 or 1")
        Integer scope,

        @Schema(description = "一级分类名称", example = "编程语言专家")
        @Size(max = 128, message = "classify must be at most 128 characters")
        String classify,

        @Schema(description = "二级分类名称", example = "Python")
        @Size(max = 128, message = "childClassify must be at most 128 characters")
        String childClassify,

        @Schema(description = "是否生成后续问题", example = "true")
        Boolean chatSuggest,

        @Schema(description = "聊天背景")
        @Size(max = 1024, message = "uiBg must be at most 1024 characters")
        String uiBg,

        @Schema(description = "评分，范围 0-10", example = "5.0")
        @DecimalMin(value = "0.0", message = "score must be greater than or equal to 0")
        @DecimalMax(value = "10.0", message = "score must be less than or equal to 10")
        BigDecimal score,

        @Schema(description = "发布可见性：PRIVATE / PUBLIC", example = "PUBLIC")
        String visibility,

        @Schema(description = "发布状态：DRAFT / PUBLISHED / DELETED", example = "PUBLISHED")
        String publishStatus,

        @Schema(description = "标签列表")
        @Size(max = 20, message = "tags must contain at most 20 entries")
        List<@Size(max = 64, message = "each tag must be at most 64 characters") String> tags,

        @Schema(description = "对话模型 ID 列表；最多一个，为空表示跟随站点默认模型")
        @Size(max = 1, message = "每个智能体只能绑定一个对话模型")
        List<Long> modelIds,

        @Schema(description = "默认模型 ID；与 modelIds 里那一个相同", example = "1")
        Long defaultModelId,

        @Schema(description = "能力 key 列表")
        @Size(max = 20, message = "abilityKeys must contain at most 20 entries")
        List<@Size(max = 128, message = "each abilityKey must be at most 128 characters") String> abilityKeys,

        @Schema(description = "推荐问题列表")
        @Size(max = 20, message = "questions must contain at most 20 entries")
        List<@Size(max = 512, message = "each question must be at most 512 characters") String> questions,

        @Schema(description = """
                引用的电商工具 key（commerce_tools.tool_key）。留空/不传 = 纯对话形态；
                传空串 = 取消引用；传 key = 详情页渲染该工具的表单面板。
                """,
                example = "one-click-background")
        @Size(max = 128, message = "linkedToolKey must be at most 128 characters")
        String linkedToolKey,

        @Schema(description = "智能体左侧配置面板 schema；null 保持原值，空对象清空面板")
        Map<String, Object> uiConfig
) {

    public AgentUpsertRequest toUpsertRequest() {
        return new AgentUpsertRequest(
                name,
                description,
                iconUrl,
                systemPrompt,
                chatPrologue,
                categoryId,
                uiType,
                scope,
                classify,
                childClassify,
                chatSuggest,
                uiBg,
                score,
                visibility,
                publishStatus,
                tags,
                modelIds,
                defaultModelId,
                abilityKeys,
                questions);
    }
}
