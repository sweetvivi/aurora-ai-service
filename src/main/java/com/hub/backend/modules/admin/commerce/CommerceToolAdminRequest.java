package com.hub.backend.modules.admin.commerce;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 管理端创建 / 更新电商工具的请求体。
 *
 * <p>语义与 {@code /api/admin/v1/agents} 保持一致：<b>字段为 null 表示保持原值</b>，
 * 因此调整一个排序号不必回填整份表单 schema。</p>
 *
 * <p>清空语义：{@code formSchema} / {@code uploadRoles} 传空对象、{@code retouchModes}
 * 传空数组，表示把该列置空（formSchema 置空后前台退回按 {@code form} 推导的旧渲染）。
 * 只有重新传值才会覆盖 —— "没传"和"传空"在这里是两件事。</p>
 *
 * <p>{@code toolKey} 只在创建时生效。它是前台提交生成请求时携带的稳定标识，
 * 也是历史记录的关联键，更新时不允许修改（尝试修改会返回 400）。</p>
 */
@Schema(name = "CommerceToolAdminRequest", description = "后台创建或更新电商工具")
public record CommerceToolAdminRequest(
        @Schema(description = "所属站点 ID；null 表示平台全局。创建后不建议修改")
        Long siteId,

        @Schema(description = "场景：commerce 电商（默认）/ painting 绘画。决定工具出现在 /e-commerce 还是 /painting",
                example = "commerce")
        String scene,

        @Schema(description = "工具稳定标识，仅创建时生效。小写字母 / 数字 / 连字符",
                example = "display-stand-replace")
        String toolKey,

        @Schema(description = "所属分类（左栏一级目录）", example = "商品替换")
        String category,

        @Schema(description = "工具名称", example = "展示台座替换大师")
        String name,

        @Schema(description = "一句话简介，展示在工具卡片上")
        String description,

        @Schema(description = "提交给图像接口的提示词模板")
        String prompt,

        @Schema(description = "参数面板形态：background / pattern / placeholder / replace / retouch / try-on",
                example = "background")
        String form,

        @Schema(description = "图标：remixicon 类名（如 ri-store-3-line）或单个汉字", example = "ri-store-3-line")
        String glyph,

        @Schema(description = """
                自定义图标图片地址；非空时前台优先显示它，glyph 被忽略。
                传空字符串表示清除图片、回到文字图标。
                """, example = "https://cdn.example.com/icons/store.png")
        String iconUrl,

        @Schema(description = "主题色（#RRGGBB）", example = "#f07a38")
        String accent,

        @Schema(description = "上传槽位文案，键为 upload role，值为 {label, description}")
        Map<String, Object> uploadRoles,

        @Schema(description = "修图模式选项；仅 form=retouch 时前台使用")
        List<String> retouchModes,

        @Schema(description = """
                工具自己的表单定义：uploads / fields / guide / gallery / credits。
                gallery 即「案例轮播」的图片与文案，前台在没有生成结果时展示。
                传 {} 表示清空。
                """)
        Map<String, Object> formSchema,

        @Schema(description = "同分类内的排序号，越小越靠前", example = "10")
        Integer sortOrder,

        @Schema(description = "状态：ACTIVE 正常 / DELETED 软删除", example = "ACTIVE")
        String status
) {
}
