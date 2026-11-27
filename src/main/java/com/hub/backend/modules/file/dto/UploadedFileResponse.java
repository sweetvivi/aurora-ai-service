package com.hub.backend.modules.file.dto;

import com.hub.backend.modules.file.entity.FileAssetType;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Metadata of an uploaded attachment.
 */
@Schema(name = "UploadedFile", description = "附件元数据")
public record UploadedFileResponse(
        @Schema(description = "附件 ID", example = "1") Long id,
        @Schema(description = "原始文件名", example = "screenshot.png") String originalName,
        @Schema(description = "MIME 类型", example = "image/png") String mimeType,
        @Schema(description = "扩展名", example = "png") String extension,
        @Schema(description = "资源用途：reference / generated / edit_result / chat_attachment / avatar",
                example = "chat_attachment") FileAssetType assetType,
        @Schema(description = "字节数", example = "20480") long sizeBytes,
        @Schema(description = "SHA-256 校验和") String checksumSha256,
        @Schema(description = "状态：UPLOADED / DELETED", example = "UPLOADED") String status,
        @Schema(description = "所属会话 ID，未关联时为 null", example = "1") Long conversationId,
        @Schema(description = "关联的消息 ID，未关联时为 null") Long messageId,
        @Schema(description = "下载地址", example = "/api/v1/files/1/content") String url,
        @Schema(description = "上传时间") LocalDateTime createdAt
) {

    public static UploadedFileResponse from(UploadedFileEntity file) {
        return new UploadedFileResponse(
                file.getId(),
                file.getOriginalName(),
                file.getMimeType(),
                file.getExtension(),
                file.getAssetType(),
                file.getSizeBytes(),
                file.getChecksumSha256(),
                file.getStatus(),
                file.getConversationId(),
                file.getMessageId(),
                "/api/v1/files/%d/content".formatted(file.getId()),
                file.getCreatedAt());
    }
}
