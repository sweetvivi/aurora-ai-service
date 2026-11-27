package com.hub.backend.modules.aicanvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Wire contracts for the AI Canvas page.
 *
 * <p>The frontend owns the workflow document, so nodes, groups, viewport and message references are
 * carried as JSON rather than mirrored into a second server-side schema.</p>
 */
public final class AiCanvasDtos {

    private static final ZoneId PERSISTED_TIME_ZONE = ZoneId.systemDefault();

    private AiCanvasDtos() {
    }

    /**
     * Timestamps are stored without an offset by the existing schema. Convert them to an explicit
     * UTC instant at the API boundary so every canvas response carries a timezone.
     */
    public static Instant toUtc(LocalDateTime value) {
        return value == null ? null : value.atZone(PERSISTED_TIME_ZONE).toInstant();
    }

    @Schema(name = "AiCanvasWorkflowSaveRequest", description = "创建或覆盖保存 AI 画布")
    public record WorkflowSaveRequest(
            @Size(max = 64, message = "id must be at most 64 characters")
            String id,
            @NotBlank(message = "title must not be blank")
            @Size(max = 255, message = "title must be at most 255 characters")
            String title,
            @Size(max = 1024, message = "description must be at most 1024 characters")
            String description,
            @NotNull(message = "nodes must not be null")
            JsonNode nodes,
            @NotNull(message = "groups must not be null")
            JsonNode groups,
            @NotNull(message = "viewport must not be null")
            JsonNode viewport,
            @Size(max = 64, message = "coverKey must be at most 64 characters")
            String coverKey,
            @Schema(accessMode = Schema.AccessMode.READ_ONLY,
                    description = "只读字段；封面 URL 由 coverKey 生成，请求中回传会被忽略")
            @Size(max = 1024, message = "coverUrl must be at most 1024 characters")
            String coverUrl
    ) {
    }

    @Schema(name = "AiCanvasWorkflow", description = "完整 AI 画布")
    public record WorkflowResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            String title,
            String description,
            JsonNode nodes,
            JsonNode groups,
            JsonNode viewport,
            Instant createdAt,
            Instant updatedAt,
            String coverKey,
            String coverUrl
    ) {
    }

    @Schema(name = "AiCanvasWorkflowSummary", description = "AI 画布列表项")
    public record WorkflowSummaryResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            String title,
            String description,
            Instant createdAt,
            Instant updatedAt,
            int nodeCount,
            String coverKey,
            String coverUrl
    ) {
    }

    @Schema(name = "AiCanvasWorkflowId", description = "保存结果")
    public record WorkflowIdResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String id) {
    }

    @Schema(name = "AiCanvasTitleRequest", description = "重命名 AI 画布")
    public record TitleRequest(
            @NotBlank(message = "title must not be blank")
            @Size(max = 255, message = "title must be at most 255 characters")
            String title
    ) {
    }

    @Schema(name = "AiCanvasCoverRequest", description = "设置 AI 画布封面")
    public record CoverRequest(
            @NotBlank(message = "coverKey must not be blank")
            @Size(max = 64, message = "coverKey must be at most 64 characters")
            String coverKey
    ) {
    }

    @Schema(name = "AiCanvasAssetLinkRequest", description = "把已有附件加入素材库（只存引用）")
    public record AssetLinkRequest(
            @Schema(description = "附件 ID", example = "26", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull(message = "fileId 不能为空")
            Long fileId,
            @Schema(description = "素材分类：character / scene / object / style / sound / other",
                    example = "other", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "category 不能为空")
            String category,
            @Schema(description = "覆盖显示名，缺省用附件原文件名")
            @Size(max = 512, message = "name 最多 512 个字符")
            String name,
            @Size(max = 4000, message = "prompt 最多 4000 个字符")
            String prompt,
            @Size(max = 128, message = "model 最多 128 个字符")
            String model
    ) {
    }

    @Schema(name = "AiCanvasAsset", description = "AI 画布素材")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String key,
            String name,
            String category,
            String type,
            String prompt,
            String model,
            String mimeType,
            long sizeBytes,
            Instant createdAt,
            @Schema(description = "引用的附件 ID；素材自己存字节时为 null")
            Long sourceFileId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String url
    ) {
    }

    @Schema(name = "AiCanvasGenerationResult", description = "AI 画布同步生成结果")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean async,
            String taskId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"running", "succeeded", "failed"})
            String status,
            String url,
            String aspectRatio,
            String videoUrl
    ) {
        public static GenerationResponse image(String url, String aspectRatio) {
            return new GenerationResponse(false, null, "succeeded", url, aspectRatio, null);
        }

        public static GenerationResponse video(String url) {
            return new GenerationResponse(false, null, "succeeded", null, null, url);
        }

        public static GenerationResponse pending(String taskId) {
            return new GenerationResponse(true, taskId, "running", null, null, null);
        }
    }

    @Schema(name = "AiCanvasVideoGenerationRequest", description = "视频生成请求")
    public record VideoGenerationRequest(
            @NotBlank(message = "prompt must not be blank")
            @Size(max = 4000, message = "prompt must be at most 4000 characters")
            String prompt,
            @Size(max = 128, message = "videoModel must be at most 128 characters")
            String videoModel,
            @Size(max = 32) String aspectRatio,
            @Size(max = 32) String resolution,
            @Min(value = 1, message = "duration must be at least 1")
            @Max(value = 60, message = "duration must be at most 60")
            Integer duration,
            String imageBase64,
            String lastFrameBase64,
            @Schema(description = "参考图模式的参考图：data URL、裸 base64、平台附件地址或画布素材地址，"
                    + "按数组顺序作为参考图 1、2…（与 lastFrameBase64 互斥）。张数上限由模型的"
                    + " maxReferenceImages 决定，这里只兜住一个绝对上限：xAI reference_to_video 最多 14 张。")
            @Size(max = 14, message = "最多支持 14 张参考图")
            List<String> referenceImages,
            @Size(max = 4096) String motionReferenceUrl
    ) {
    }

    @Schema(name = "AiCanvasVideoTrimRequest", description = "视频裁剪请求")
    public record VideoTrimRequest(
            @NotBlank(message = "videoDataUrl must not be blank")
            @Schema(description = "base64 视频 Data URL，或可公网访问的 http(s) 视频地址",
                    example = "https://cdn.example.com/input.mp4")
            String videoDataUrl,
            @NotNull(message = "startTime must not be null")
            @Min(value = 0, message = "startTime must not be negative")
            Double startTime,
            @NotNull(message = "endTime must not be null")
            @Min(value = 0, message = "endTime must not be negative")
            Double endTime
    ) {
    }

    @Schema(name = "AiCanvasTask", description = "异步生成任务")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String taskId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"running", "succeeded", "failed"})
            String status,
            Integer progress,
            String url,
            String videoUrl,
            String error
    ) {
    }

    @Schema(name = "AiCanvasVideoTask", description = "视频生成历史")
    public record VideoTaskResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String taskId,
            String prompt,
            Instant createdAt,
            String videoUrl,
            String model,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"running", "succeeded", "failed"})
            String status,
            String errorMessage
    ) {
    }

    @Schema(name = "AiCanvasVideoTaskList", description = "视频生成历史列表")
    public record VideoTaskListResponse(List<VideoTaskResponse> tasks) {
    }

    @Schema(name = "AiCanvasPromptOptimizeRequest", description = "提示词优化请求")
    public record PromptOptimizeRequest(
            @NotBlank(message = "prompt must not be blank")
            @Size(max = 4000, message = "prompt must be at most 4000 characters")
            String prompt
    ) {
    }

    @Schema(name = "AiCanvasPromptResponse", description = "优化后的提示词")
    public record PromptResponse(String optimizedPrompt) {
    }

    @Schema(name = "AiCanvasDescribeImageRequest", description = "图片描述请求")
    public record DescribeImageRequest(
            @NotBlank(message = "imageUrl must not be blank")
            @Size(max = 4096, message = "imageUrl must be at most 4096 characters")
            String imageUrl,
            @Size(max = 4000, message = "prompt must be at most 4000 characters")
            String prompt
    ) {
    }

    @Schema(name = "AiCanvasImageDescription", description = "图片描述结果")
    public record ImageDescriptionResponse(String description) {
    }

    @Schema(name = "AiCanvasChatSession", description = "灵感画板会话")
    public record ChatSessionResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            int messageCount
    ) {
    }

    @Schema(name = "AiCanvasChatMessage", description = "灵感画板消息")
    public record ChatMessageResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String sessionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String role,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String content,
            Instant createdAt,
            JsonNode refs
    ) {
    }

    @Schema(name = "AiCanvasChatMessages", description = "灵感画板消息列表")
    public record ChatMessageListResponse(List<ChatMessageResponse> messages) {
    }

    @Schema(name = "AiCanvasChatRequest", description = "发送灵感画板消息")
    public record ChatRequest(
            @NotBlank(message = "sessionId must not be blank")
            @Size(max = 64, message = "sessionId must be at most 64 characters")
            String sessionId,
            @NotBlank(message = "message must not be blank")
            @Size(max = 8000, message = "message must be at most 8000 characters")
            String message,
            JsonNode media
    ) {
    }

    @Schema(name = "AiCanvasChatResponse", description = "灵感画板回复")
    public record ChatResponse(String response, String topic) {
    }

    @Schema(name = "AiCanvasCharacterDescription", description = "分镜角色设定")
    public record CharacterDescription(
            @Size(max = 255) String name,
            @Size(max = 4000) String description
    ) {
    }

    @Schema(name = "AiCanvasStoryRequest", description = "故事构思请求")
    public record StoryRequest(
            List<CharacterDescription> characterDescriptions,
            JsonNode referenceImages
    ) {
    }

    @Schema(name = "AiCanvasStoryResponse", description = "故事文本")
    public record StoryResponse(String story) {
    }

    @Schema(name = "AiCanvasOptimizeStoryRequest", description = "故事优化请求")
    public record OptimizeStoryRequest(
            @NotBlank(message = "story must not be blank")
            @Size(max = 20000, message = "story must be at most 20000 characters")
            String story,
            List<@Size(max = 255) String> characterNames
    ) {
    }

    @Schema(name = "AiCanvasScriptsRequest", description = "分镜脚本生成请求")
    public record ScriptsRequest(
            @NotBlank(message = "story must not be blank")
            @Size(max = 20000, message = "story must be at most 20000 characters")
            String story,
            List<CharacterDescription> characterDescriptions,
            @NotNull(message = "sceneCount must not be null")
            @Min(value = 1, message = "sceneCount must be at least 1")
            @Max(value = 20, message = "sceneCount must be at most 20")
            Integer sceneCount,
            JsonNode referenceImages
    ) {
    }

    @Schema(name = "AiCanvasScript", description = "单个分镜脚本")
    public record ScriptResponse(
            int sceneNumber,
            String description,
            String cameraAngle,
            @Schema(description = "镜头运动，例如 缓推 / 横移 / 升降 / 手持跟拍")
            String cameraMovement,
            @Schema(description = "光线与色调，例如 黄昏侧逆光")
            String lighting,
            String mood,
            String prompt
    ) {
    }

    @Schema(name = "AiCanvasScripts", description = "分镜脚本结果")
    public record ScriptsResponse(
            List<ScriptResponse> scripts,
            String styleAnchor,
            JsonNode characterDNA
    ) {
    }

    @Schema(name = "AiCanvasCompositeRequest", description = "分镜合成请求")
    public record CompositeRequest(
            @NotNull(message = "scripts must not be null")
            JsonNode scripts,
            @Size(max = 8000) String styleAnchor,
            JsonNode characterDNA,
            @Min(value = 1, message = "sceneCount must be at least 1")
            @Max(value = 20, message = "sceneCount must be at most 20")
            Integer sceneCount,
            JsonNode referenceImages
    ) {
    }

    @Schema(name = "AiCanvasImageUrl", description = "图片地址结果")
    public record ImageUrlResponse(String imageUrl) {
    }

    @Schema(name = "AiCanvasLocalModel", description = "本地模型目录项")
    public record LocalModelResponse(
            String id,
            String name,
            String type,
            String description
    ) {
    }

    @Schema(name = "AiCanvasLocalModelRequest", description = "本地模型生成请求")
    public record LocalModelGenerateRequest(
            @NotBlank(message = "model must not be blank")
            @Size(max = 128, message = "model must be at most 128 characters")
            String model,
            @NotBlank(message = "prompt must not be blank")
            @Size(max = 4000, message = "prompt must be at most 4000 characters")
            String prompt,
            @Size(max = 32) String aspectRatio,
            @Size(max = 32) String resolution
    ) {
    }
}
