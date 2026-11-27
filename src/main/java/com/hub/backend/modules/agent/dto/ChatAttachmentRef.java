package com.hub.backend.modules.agent.dto;

import com.hub.backend.modules.file.entity.UploadedFileEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 消息附带的文件引用（图片或音频）。
 *
 * <p>只存引用，不存二进制：附件本体在 {@code uploaded_files} 与磁盘上。重建上下文时按
 * {@code id} 回读，文件已被用户删掉就跳过该附件，不影响整段对话读取。</p>
 */
@Schema(name = "ChatAttachmentRef", description = "消息附带的图片或音频引用")
public record ChatAttachmentRef(
        @Schema(description = "附件 ID", example = "5") Long id,
        @Schema(description = "MIME 类型", example = "image/png") String mimeType,
        @Schema(description = "原始文件名", example = "shirt.png") String originalName,
        @Schema(description = "字节数", example = "20480") long sizeBytes,
        @Schema(description = "读取地址（需带 Bearer）",
                example = "/api/v1/files/5/content") String url
) {

    public static ChatAttachmentRef of(UploadedFileEntity file) {
        return new ChatAttachmentRef(
                file.getId(),
                file.getMimeType(),
                file.getOriginalName(),
                file.getSizeBytes(),
                "/api/v1/files/%d/content".formatted(file.getId()));
    }
}
