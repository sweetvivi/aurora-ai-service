package com.hub.backend.modules.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 一张附件被哪些页面引用。
 *
 * <p>删除是不可逆的（会连同存储对象一起清理），所以前端在弹确认框之前先问一次这里：
 * 有引用就把「这张图还出现在电商历史 / AI 教育对话 / 画布素材里」写在提示里，
 * 而不是删完让别的页面破图。</p>
 */
@Schema(name = "FileUsageResponse", description = "附件被其它页面引用的情况")
public record FileUsageResponse(
        @Schema(description = "附件 ID", example = "123")
        Long fileId,

        @Schema(description = "引用总数（各分组之和）", example = "3")
        int total,

        @Schema(description = "按页面分组的引用明细，没有引用时为空数组")
        List<Group> groups
) {

    /**
     * 一个页面上的引用。
     *
     * @param module  机器可读的模块标识，前端据此决定措辞，不随文案变化
     * @param label   给人看的页面名
     * @param count   该页面引用了几处
     * @param samples 最多三条上下文（作品名 / 工具名 / 对话标题），用于让用户认出是哪张图
     */
    @Schema(name = "FileUsageGroup")
    public record Group(
            @Schema(description = "模块标识", example = "commerce")
            String module,

            @Schema(description = "页面名称", example = "电商历史")
            String label,

            @Schema(description = "引用数量", example = "2")
            int count,

            @Schema(description = "上下文样本，最多三条")
            List<String> samples
    ) {
    }

    public static FileUsageResponse empty(Long fileId) {
        return new FileUsageResponse(fileId, 0, List.of());
    }
}
