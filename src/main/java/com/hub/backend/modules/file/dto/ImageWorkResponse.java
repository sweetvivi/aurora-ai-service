package com.hub.backend.modules.file.dto;

import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One picture in the shared work list.
 *
 * <p>Since V88 the image-generation page, AI drawing, the smart-editing workbench and the canvas
 * material library all read the same rows: a picture produced anywhere shows up everywhere. This
 * view is deliberately smaller than {@link UploadedFileResponse} — it carries what a card renders
 * (prompt included) and leaves out checksum / status, which no gallery uses.</p>
 */
@Schema(name = "ImageWork", description = "共用作品列表里的一张图")
public record ImageWorkResponse(
        @Schema(description = "附件 ID", example = "42") Long id,
        @Schema(description = "原始文件名", example = "generated-1.png") String originalName,
        @Schema(description = "MIME 类型", example = "image/png") String mimeType,
        @Schema(description = "来源页面：image_generation / painting / smart_edit / canvas / agent",
                example = "painting") String source,
        @Schema(description = "资源用途：generated / edit_result", example = "edit_result")
        FileAssetType assetType,
        @Schema(description = "出图用的提示词，历史数据可能为空") String prompt,
        @Schema(description = "出图用的模型（上游 key）；V91 之前的数据为空", example = "gpt-image-1")
        String model,
        @Schema(description = "所属会话 ID，未关联时为 null") Long conversationId,
        @Schema(description = "字节数", example = "20480") long sizeBytes,
        @Schema(description = "下载地址", example = "/api/v1/files/42/content") String url,
        @Schema(description = "生成时间") LocalDateTime createdAt
) {

    public static ImageWorkResponse from(UploadedFileEntity file) {
        return new ImageWorkResponse(
                file.getId(),
                file.getOriginalName(),
                file.getMimeType(),
                file.getSourceValue(),
                file.getAssetType(),
                file.getPrompt(),
                file.getModel(),
                file.getConversationId(),
                file.getSizeBytes(),
                "/api/v1/files/%d/content".formatted(file.getId()),
                file.getCreatedAt());
    }
}
