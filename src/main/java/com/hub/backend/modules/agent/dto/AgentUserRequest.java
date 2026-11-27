package com.hub.backend.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * User-plane payload for creating and maintaining an account-owned agent.
 *
 * <p>Ownership, agent type, scope, visibility, publish state and score are deliberately absent.
 * They are assigned by the service so a user cannot create an official, published or otherwise
 * privileged record through the public API.</p>
 */
@Schema(name = "AgentUserRequest", description = "用户创建或更新自己的智能体")
public record AgentUserRequest(
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

        @Schema(description = "分类 ID", example = "1")
        Long categoryId,

        @Schema(description = "前端渲染类型：1 通用，2 自定义", example = "1")
        @Min(value = 1, message = "uiType must be 1 or 2")
        @Max(value = 2, message = "uiType must be 1 or 2")
        Integer uiType,

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

        @Schema(description = "可选的电商特化工具 key；为空表示通用助手")
        @Size(max = 128, message = "linkedToolKey must be at most 128 characters")
        String linkedToolKey
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
                null,
                classify,
                childClassify,
                chatSuggest,
                uiBg,
                null,
                null,
                null,
                tags,
                modelIds,
                defaultModelId,
                abilityKeys,
                questions);
    }
}
