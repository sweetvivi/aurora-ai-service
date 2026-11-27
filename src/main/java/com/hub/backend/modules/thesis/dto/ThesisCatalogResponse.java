package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Reference data for the thesis workbench form.
 *
 * <p>Served from the backend so a new discipline or word range does not require a frontend deploy,
 * and so the numbers the outline engine was tuned against stay in one place.</p>
 *
 * @param educations   education levels, each pinning a template and a default depth
 * @param outlineDepths two or three level outline
 * @param paperTypes   what kind of document the user is writing
 * @param disciplines  first level discipline with its majors
 * @param wordOptions  word count ranges
 */
@Schema(name = "ThesisCatalog", description = "论文工作台表单选项")
public record ThesisCatalogResponse(
        @Schema(description = "学历层次") List<EducationOption> educations,
        @Schema(description = "大纲结构") List<OutlineDepthOption> outlineDepths,
        @Schema(description = "论文类型") List<PaperTypeOption> paperTypes,
        @Schema(description = "学科与专业") List<DisciplineOption> disciplines,
        @Schema(description = "字数档位") List<WordOption> wordOptions,
        @Schema(description = "服务端是否配置了可用的上游模型（决定能否用真实 AI 生成大纲）",
                example = "false") boolean modelEnabled,
        @Schema(description = "可用的对话模型 key 列表，供前端选择；空数组表示只能用模板", example = "[]")
        List<ModelOption> models
) {

    @Schema(name = "ThesisModelOption", description = "可选的对话模型")
    public record ModelOption(
            @Schema(description = "模型 key，生成时作为 model 字段提交", example = "deepseek-v4.1-flash")
            String key,
            @Schema(description = "展示名", example = "DeepSeek V4.1 Flash") String label,
            @Schema(description = "厂商", example = "DeepSeek") String company,
            // 下面几项与站点 `/models/families` 对齐：论文页的模型下拉要和「模型对话」栏目
            // 展示同一套分组与能力标记，否则同一台机器上两个地方会显得不一致。
            @Schema(description = "家族 key，用于按厂商分组", example = "deepseek") String familyKey,
            @Schema(description = "家族展示名", example = "DeepSeek") String familyLabel,
            @Schema(description = "能力：chat / image / …", example = "chat") String capability,
            @Schema(description = "是否支持推理链", example = "true") boolean thinking,
            @Schema(description = "描述", example = "极速通用对话模型") String description,
            @Schema(description = "标签", example = "[\"思考\"]") List<String> tags,
            @Schema(description = "是否为该家族的默认变体", example = "false") boolean isDefault
    ) {
    }

    @Schema(name = "ThesisEducationOption", description = "学历层次")
    public record EducationOption(
            @Schema(description = "展示名，也是提交值", example = "本科在读") String label,
            @Schema(description = "对应的大纲模板", example = "UNDERGRAD") String tier,
            @Schema(description = "默认大纲层级", example = "3") int defaultDepth,
            @Schema(description = "默认字数", example = "8000") int defaultWords
    ) {
    }

    @Schema(name = "ThesisOutlineDepthOption", description = "大纲结构")
    public record OutlineDepthOption(
            @Schema(description = "层级：2 或 3", example = "3") int value,
            @Schema(description = "名称", example = "三级大纲") String label,
            @Schema(description = "适用说明", example = "适用于篇幅长,主题复杂的深入研究") String description
    ) {
    }

    @Schema(name = "ThesisPaperTypeOption", description = "论文类型")
    public record PaperTypeOption(
            @Schema(description = "展示名", example = "开题报告") String label,
            @Schema(description = "短名，用于窄栏", example = "开题报告") String shortLabel,
            @Schema(description = "一句话说明", example = "选题依据与研究设计") String description,
            @Schema(description = "对应的大纲模板", example = "PROPOSAL") String tier,
            @Schema(description = "关键字提示：这类文本包含的主要部分，界面据此显示，也说明生成结果长什么样",
                    example = "[\"摘要\",\"绪论\",\"文献综述\",\"研究设计\",\"结论\"]")
            List<String> keywords
    ) {
    }

    @Schema(name = "ThesisDisciplineOption", description = "学科与下属专业")
    public record DisciplineOption(
            @Schema(description = "一级学科", example = "工学") String name,
            @Schema(description = "二级专业") List<String> majors
    ) {
    }

    @Schema(name = "ThesisWordOption", description = "字数档位")
    public record WordOption(
            @Schema(description = "提交值，取区间上限", example = "8000") int value,
            @Schema(description = "展示文本", example = "5000-8000字") String label,
            @Schema(description = "适用说明", example = "本科期末 / 期刊短文") String hint
    ) {
    }
}
