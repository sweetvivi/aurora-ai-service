package com.hub.backend.modules.thesis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 「数据模型与代码」 switch, tested at the prompt/formatting level.
 *
 * <p>No model is called: what can regress silently is the instruction (does it reach the prompt at
 * all?) and the post-processing (does it keep the code?), not the model's wording.</p>
 */
class ThesisDataModelAppendixTest {

    private static ThesisFullTextGenerator.Brief brief(boolean dataModel) {
        return new ThesisFullTextGenerator.Brief(
                "基于深度学习的短视频推荐算法优化", "本科在读", "论文助手", "工学", "软件工程",
                3, 8000, false, false, dataModel, "");
    }

    @Test
    void enablesCodeBlocksOnlyWhenTheSwitchIsOn() {
        String on = ThesisFullTextGenerator.systemPrompt(brief(true));
        String off = ThesisFullTextGenerator.systemPrompt(brief(false));

        // 不开时仍然禁止代码块，行为与之前一致
        assertThat(off).doesNotContain("```");
        assertThat(off).contains("不要引入实证假设检验框架");

        // 打开后必须解除「禁止代码块」，否则「与代码」永远生成不出来
        assertThat(on).contains("```");
        assertThat(on).contains("附录");
        assertThat(on).contains("数据表结构");
    }

    @Test
    void appendixPromptAsksForSchemaCodeAndEnvironment() {
        String prompt = ThesisFullTextGenerator.appendixPrompt(brief(true), "- 第一章 绪论：背景");

        assertThat(prompt).contains("数据模型设计").contains("关键代码实现").contains("实现说明");
        assertThat(prompt).contains("建表语句").contains("TODO").contains("尚未实测");
        // 正文摘要带进去，附录才不会另起一套实体
        assertThat(prompt).contains("第一章 绪论");
    }

    @Test
    void keepsCommentsInsideCodeFences() {
        String content = """
                # 附录 A 数据模型与关键代码

                一、数据模型设计

                ```sql
                -- 用户表
                create table t_user (
                  id bigint primary key
                );
                ```

                ```python
                # 余弦相似度，用于召回阶段
                def cosine(a, b):
                    return 0.0
                ```
                """;

        String body = ThesisFullTextGenerator.normaliseBody(content, ThesisFullTextGenerator.APPENDIX_TITLE);

        // 「# 附录 A…」这行标题被去掉，但 Python 注释不能被当成标题删掉
        assertThat(body).doesNotStartWith("# 附录");
        assertThat(body).contains("# 余弦相似度，用于召回阶段");
        assertThat(body).contains("```sql").contains("create table t_user");
    }

    @Test
    void recognisesTheAppendixDespiteRenaming() {
        assertThat(ThesisFullTextGenerator.isDataAppendix("附录 A 数据模型与关键代码")).isTrue();
        assertThat(ThesisFullTextGenerator.isDataAppendix("附录B 核心代码")).isTrue();
        assertThat(ThesisFullTextGenerator.isDataAppendix("附录 调查问卷")).isFalse();
        assertThat(ThesisFullTextGenerator.isDataAppendix("第三章 系统设计")).isFalse();
        assertThat(ThesisFullTextGenerator.isDataAppendix(null)).isFalse();
    }
}
