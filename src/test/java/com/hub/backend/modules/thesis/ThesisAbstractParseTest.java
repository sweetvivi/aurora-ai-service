package com.hub.backend.modules.thesis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中英文摘要的解析规则。
 *
 * <p>这里锁住的不是「模型写得好看」，而是「四种标记切分正确」。摘要串段是这个功能
 * 最伤人的失败方式：如果英文摘要被切进中文字段，导出的论文会在中文摘要里出现一整段英文，
 * 提交时直接被判格式错误，而用户往往在打印之后才发现。</p>
 *
 * <p>所以测试重点全在**模型没守规矩**的写法上：全角冒号、裸标记、长短标记嵌套、
 * 顿号与逗号分隔的关键词、以及干脆漏掉某个标记。</p>
 */
class ThesisAbstractParseTest {

    @Test
    void parsesTheFourSectionsInOrder() {
        String raw = """
                【中文摘要】
                针对金融舆情信息分散、人工研判滞后的问题，本文构建了基于大语言模型的分析系统。
                系统采用分层架构，先做文本清洗与情感倾向判定，再聚合为舆情指数。实验表明该方案可用。

                【中文关键词】
                大语言模型；金融舆情；情感分析；文本挖掘

                【英文摘要】
                To address the fragmentation of financial public opinion, this paper builds an
                analysis system based on large language models. The system adopts a layered
                architecture and aggregates sentiment into an index.

                【英文关键词】
                large language model; financial public opinion; sentiment analysis
                """;

        ThesisFullTextGenerator.Abstract parsed = ThesisFullTextGenerator.parseAbstract(raw);

        assertTrue(parsed.chinese().startsWith("针对金融舆情信息分散"), parsed.chinese());
        assertEquals("大语言模型；金融舆情；情感分析；文本挖掘", parsed.chineseKeywords());
        assertTrue(parsed.english().startsWith("To address the fragmentation"), parsed.english());
        assertEquals("large language model; financial public opinion; sentiment analysis",
                parsed.englishKeywords());
        // 四段之间不能串：中文字段里不该出现英文摘要的开头
        assertTrue(!parsed.chinese().contains("To address"), parsed.chinese());
        assertTrue(!parsed.english().contains("大语言模型"));
    }

    @Test
    void toleratesFullWidthColonsAndBareMarkers() {
        // 模型常把「【】」省掉、把冒号写成全角
        String raw = """
                中文摘要：本文研究短视频推荐算法。
                中文关键词：推荐算法、协同过滤、深度强化学习
                英文摘要: This paper studies short video recommendation.
                英文关键词: recommendation; collaborative filtering
                """;

        ThesisFullTextGenerator.Abstract parsed = ThesisFullTextGenerator.parseAbstract(raw);

        assertEquals("本文研究短视频推荐算法。", parsed.chinese());
        // 顿号也要归一成中文分号
        assertEquals("推荐算法；协同过滤；深度强化学习", parsed.chineseKeywords());
        assertEquals("This paper studies short video recommendation.", parsed.english());
        assertEquals("recommendation; collaborative filtering", parsed.englishKeywords());
    }

    @Test
    void doesNotLeaveHalfWordsWhenShortMarkerNestsInLongMarker() {
        // 关键回归点：「中文摘要」包含「摘要」。若先替换短标记，会留下「中文」两个字
        // 粘在摘要开头；同理「中文关键词」包含「关键词」。
        String raw = "中文摘要：正文内容。\n中文关键词：甲；乙\n英文摘要：Body.\n英文关键词：a; b";

        ThesisFullTextGenerator.Abstract parsed = ThesisFullTextGenerator.parseAbstract(raw);

        assertEquals("正文内容。", parsed.chinese());
        assertEquals("甲；乙", parsed.chineseKeywords());
        assertEquals("Body.", parsed.english());
        assertEquals("a; b", parsed.englishKeywords());
        // 任何一个字段都不该残留标记字
        for (String field : new String[]{parsed.chinese(), parsed.chineseKeywords(),
                parsed.english(), parsed.englishKeywords()}) {
            assertTrue(!field.contains("中文") && !field.contains("关键词")
                    && !field.contains("摘要"), field);
        }
    }

    @Test
    void stripsSelfImposedPrefixesAndTrailingPeriods() {
        String raw = """
                【中文摘要】
                摘要：本文给出一个方法。
                【中文关键词】
                关键词：方法；验证。
                【英文摘要】
                Abstract: We propose a method.
                【英文关键词】
                Key words: method; validation.
                """;

        ThesisFullTextGenerator.Abstract parsed = ThesisFullTextGenerator.parseAbstract(raw);

        assertEquals("本文给出一个方法。", parsed.chinese());
        assertEquals("方法；验证", parsed.chineseKeywords());
        assertEquals("We propose a method.", parsed.english());
        assertEquals("method; validation", parsed.englishKeywords());
    }

    @Test
    void missingSectionYieldsEmptyFieldInsteadOfBorrowingNeighboursText() {
        // 模型漏了英文关键词：英文摘要不能把后面的东西吞进来，英文关键词必须是空
        String raw = """
                【中文摘要】
                中文正文。
                【中文关键词】
                甲；乙
                【英文摘要】
                English body.
                """;

        ThesisFullTextGenerator.Abstract parsed = ThesisFullTextGenerator.parseAbstract(raw);

        assertEquals("中文正文。", parsed.chinese());
        assertEquals("English body.", parsed.english());
        assertEquals("", parsed.englishKeywords());
        assertTrue(!parsed.isEmpty());
    }

    @Test
    void garbageOutputIsReportedAsEmptyRatherThanGuessed() {
        // 模型完全没按格式来：宁可四项为空、让界面提示重新生成，也不要乱切
        ThesisFullTextGenerator.Abstract parsed =
                ThesisFullTextGenerator.parseAbstract("这篇论文写得很好，摘要如下：值得一读。");

        assertTrue(parsed.isEmpty(), "没有标记时必须整份判空");
    }

    @Test
    void blankInputIsEmpty() {
        assertTrue(ThesisFullTextGenerator.parseAbstract(null).isEmpty());
        assertTrue(ThesisFullTextGenerator.parseAbstract("").isEmpty());
        assertTrue(ThesisFullTextGenerator.parseAbstract("   \n  ").isEmpty());
    }
}