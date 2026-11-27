package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/thesis/outlines} and {@code POST /api/v1/thesis/documents}.
 *
 * <p>The workbench form maps one-to-one onto these fields: topic, discipline + major (专业领域),
 * educationLevel (学历层次), outlineDepth (大纲结构), targetWords (目标字数) and the three value-add
 * switches. {@code paperType} only changes wording on the client and is stored for display.</p>
 *
 * @param educationLevel one of the eight labels from {@code /catalog}; unknown values fall back to
 *                       the undergraduate template rather than being rejected
 * @param outlineDepth   2 for a two level outline, 3 for the full three level tree, null = default
 */
@Schema(name = "ThesisOutlineRequest", description = "生成大纲请求")
public record ThesisOutlineRequest(
        @Schema(description = "论文题目", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "基于大语言模型的金融舆情分析系统")
        @NotBlank(message = "topic must not be blank")
        @Size(max = 60, message = "topic must be at most 60 characters")
        String topic,

        @Schema(description = "学历层次，取自 /catalog 的 educations[].label", example = "本科在读")
        @Size(max = 32, message = "educationLevel must be at most 32 characters")
        String educationLevel,

        @Schema(description = "大纲结构：2 二级 / 3 三级，缺省 3", example = "3")
        @Min(value = 2, message = "outlineDepth must be 2 or 3")
        @Max(value = 3, message = "outlineDepth must be 2 or 3")
        Integer outlineDepth,

        @Schema(description = "文本类型，仅用于展示与导出命名", example = "开题报告")
        @Size(max = 32, message = "paperType must be at most 32 characters")
        String paperType,

        @Schema(description = "一级学科", requiredMode = Schema.RequiredMode.REQUIRED, example = "工学")
        @NotBlank(message = "discipline must not be blank")
        @Size(max = 64, message = "discipline must be at most 64 characters")
        String discipline,

        @Schema(description = "二级专业", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "计算机科学与技术")
        @NotBlank(message = "major must not be blank")
        @Size(max = 128, message = "major must be at most 128 characters")
        String major,

        @Schema(description = "目标字数（取区间上限）", requiredMode = Schema.RequiredMode.REQUIRED, example = "8000")
        @NotNull(message = "targetWords must not be null")
        @Min(value = 1000, message = "targetWords must be at least 1000")
        @Max(value = 60000, message = "targetWords must be at most 60000")
        Integer targetWords,

        @Schema(description = "是否引用近三年真实文献", example = "true") Boolean referenceEnabled,
        @Schema(description = "是否同步生成开题报告与答辩 PPT 提纲", example = "true")
        Boolean attachmentsEnabled,
        @Schema(description = "是否附带数据分析模型与代码实现", example = "false")
        Boolean dataModelEnabled,

        @Schema(description = "是否用真实 AI 模型生成（需要服务端已配置上游 key）；false 或不传时用确定性模板",
                example = "false")
        Boolean useModel,

        @Schema(description = "模型 key，仅当 useModel=true 时生效；缺省用服务端默认模型",
                example = "deepseek-v4.1-flash")
        @Size(max = 128, message = "model must be at most 128 characters")
        String model,

        @Schema(description = "旧字段：大纲模板。仅在没有 educationLevel 时使用，新客户端请传 educationLevel",
                allowableValues = {"UNDERGRAD", "MASTER", "JOURNAL", "PROPOSAL"}, example = "UNDERGRAD",
                deprecated = true)
        String tier
) {
}
