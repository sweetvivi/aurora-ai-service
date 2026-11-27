package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser tests: the model's answer is untrusted input, so every shape it actually produces in
 * practice has a case here.
 */
class ThesisModelOutlineParserTest {

    private final ThesisModelOutlineGenerator generator = new ThesisModelOutlineGenerator(
            null, null, null, null, null, new ObjectMapper());

    @Test
    void parsesTheInstructedMarkdownFormat() {
        String reply = """
                # 第一章 绪论
                ## 研究背景与意义
                ### 现实背景
                ### 理论价值
                ## 国内外研究现状
                # 第二章 理论基础
                ## 核心概念
                ### 概念界定
                """;

        List<ThesisOutlineNode> tree = generator.parseOutline(reply, 3);

        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).title()).isEqualTo("第一章 绪论");
        assertThat(tree.get(0).level()).isEqualTo(1);
        assertThat(tree.get(0).children()).hasSize(2);
        assertThat(tree.get(0).children().get(0).children()).hasSize(2);
        assertThat(tree.get(0).children().get(0).children().get(0).level()).isEqualTo(3);
    }

    @Test
    void stripsCodeFencesAndPreamble() {
        String reply = """
                ```markdown
                # 第一章 绪论
                ## 研究背景
                ### 现实背景
                # 第二章 结论
                ## 主要结论
                ### 结论一
                ```
                """;

        List<ThesisOutlineNode> tree = generator.parseOutline(reply, 3);
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).title()).isEqualTo("第一章 绪论");
    }

    @Test
    void depthTwoDropsThirdLevelHeadings() {
        String reply = """
                # 第一章 绪论
                ## 研究背景
                ### 现实背景
                # 第二章 结论
                ## 主要结论
                ### 结论一
                """;

        List<ThesisOutlineNode> tree = generator.parseOutline(reply, 2);

        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).children()).hasSize(1);
        assertThat(tree.get(0).children().get(0).children()).isEmpty();
    }

    @Test
    void parsesJsonChildrenShape() {
        String reply = """
                好的，大纲如下：
                [
                  {"title": "第一章 绪论", "children": [
                    {"title": "研究背景", "children": [{"title": "现实背景"}]},
                    {"title": "研究意义"}
                  ]},
                  {"title": "第二章 结论", "children": [{"title": "主要结论"}]}
                ]
                """;

        List<ThesisOutlineNode> tree = generator.parseOutline(reply, 3);

        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).title()).isEqualTo("第一章 绪论");
        assertThat(tree.get(0).children()).hasSize(2);
        assertThat(tree.get(0).children().get(0).children()).hasSize(1);
    }

    @Test
    void parsesNumberedPlainText() {
        String reply = """
                1. 绪论
                   1.1 研究背景
                   1.2 研究意义
                2. 结论
                   2.1 主要结论
                """;

        List<ThesisOutlineNode> tree = generator.parseOutline(reply, 3);

        assertThat(tree).hasSize(2);
        // 序号被剥掉，标题只剩文字：客户端会自己编号
        assertThat(tree.get(0).title()).isEqualTo("绪论");
        assertThat(tree.get(0).children()).hasSize(2);
        assertThat(tree.get(0).children().get(0).title()).isEqualTo("研究背景");
    }

    @Test
    void rejectsAReplyWithNoOutline() {
        assertThat(generator.parseOutline("抱歉，我无法完成这个请求。", 3)).isEmpty();
        assertThat(generator.parseOutline("", 3)).isEmpty();
        assertThat(generator.parseOutline(null, 3)).isEmpty();
    }

    @Test
    void rejectsASingleHeadingAsAnOutline() {
        // 一个标题不是大纲：没有二级标题时回落模板，避免把一句客套话当结构渲染
        String reply = """
                # 第一章 绪论
                """;
        assertThat(generator.parseOutline(reply, 3)).isEmpty();
    }

    @Test
    void rejectsAnAbsurdNumberOfChapters() {
        StringBuilder reply = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            reply.append("# 第").append(i).append("章 标题\n## 小节\n");
        }
        assertThat(generator.parseOutline(reply.toString(), 3)).isEmpty();
    }
}
