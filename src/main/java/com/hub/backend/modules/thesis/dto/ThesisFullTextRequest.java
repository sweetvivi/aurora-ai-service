package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Request for the full-text generation step.
 *
 * <p>Carries the same left-hand form as the outline step, because the body must obey the same
 * requirements — plus the outline itself, which the user may have edited since it was generated.</p>
 *
 * @param documentId  saved document to write the body for; when null the fields below are used directly
 * @param useReferences whether to ground the citations in the verified reference index
 */
@Schema(name = "ThesisFullTextRequest", description = "生成全文（按章写正文）")
public record ThesisFullTextRequest(
        @Schema(description = "已保存的稿件 ID；传了就直接用库里的表单与大纲") Long documentId,
        @Schema(description = "论文题目", example = "基于大语言模型的金融舆情分析系统设计与实现") String topic,
        @Schema(description = "学历层次", example = "本科在读") String educationLevel,
        @Schema(description = "文本类型", example = "论文助手") String paperType,
        @Schema(description = "一级学科", example = "工学") String discipline,
        @Schema(description = "专业", example = "计算机科学与技术") String major,
        @Schema(description = "大纲层级：2 二级 / 3 三级", example = "3") Integer outlineDepth,
        @Schema(description = "目标字数", example = "8000") Integer targetWords,
        @Schema(description = "启用文献引用（需可用的文献索引）", example = "true") Boolean referenceEnabled,
        @Schema(description = "启用附件资料", example = "false") Boolean attachmentsEnabled,
        @Schema(description = "启用数据模型（问卷/假设检验）", example = "false") Boolean dataModelEnabled,
        @Schema(description = "用户粘贴的参考资料正文") String material,
        @Schema(description = "上传的参考资料附件 ID（来自 POST /api/v1/files）；服务端会抽取正文作为写作上下文",
                example = "[12]")
        List<Long> materialFileIds,
        @Schema(description = "大纲；缺省时按题目现场生成") List<ThesisOutlineNode> outline,
        @Schema(description = "模型 key", example = "deepseek-v4.1-flash") String model
) {
}
