package com.hub.backend.modules.thesis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字数预算：交付必须落在「目标 ~ 目标 +10%」之间。
 *
 * <p>这组测试不调用模型，只固定两件事：每章配额怎么分配，以及「最坏情况」（每章都写满上限）
 * 加起来是否仍然不破 110%。真实的压缩调用另测不到，属于运行时兜底。</p>
 */
class ThesisWordBudgetTest {

    @Test
    void budgetNeverSummsAboveTheCeilingWhenEveryChapterHitsItsMax() {
        int target = 8000;
        int chapters = 6;
        ThesisFullTextGenerator.WordBudget budget = new ThesisFullTextGenerator.WordBudget(target, chapters);

        int total = 0;
        for (int i = 0; i < chapters; i++) {
            ThesisFullTextGenerator.WordBudget.Range range = budget.next();
            // 最坏情况：模型每章都写到上限
            budget.spend(range.max());
            total += range.max();
        }

        assertThat(total).isLessThanOrEqualTo(budget.ceiling());
        assertThat(budget.ceiling()).isEqualTo(8800);
    }

    @Test
    void aChapterThatOverspendsShrinksTheQuotaOfTheNextOnes() {
        ThesisFullTextGenerator.WordBudget free = new ThesisFullTextGenerator.WordBudget(8000, 6);
        ThesisFullTextGenerator.WordBudget spent = new ThesisFullTextGenerator.WordBudget(8000, 6);

        ThesisFullTextGenerator.WordBudget.Range first = free.next();
        free.spend(first.max());
        spent.spend(first.max() * 2); // 第一章写了双倍

        assertThat(spent.next().max()).isLessThan(free.next().max());
    }

    @Test
    void theLastChapterIsNotInflatedWhenEarlierChaptersUnderwrite() {
        // 前面五章都只写了一半，最后一章不能因此被要求写三四千字
        ThesisFullTextGenerator.WordBudget budget = new ThesisFullTextGenerator.WordBudget(8000, 6);
        for (int i = 0; i < 5; i++) {
            ThesisFullTextGenerator.WordBudget.Range range = budget.next();
            budget.spend(range.min() / 2);
        }
        ThesisFullTextGenerator.WordBudget.Range last = budget.next();

        assertThat(last.max()).isLessThanOrEqualTo(2000);
    }

    @Test
    void trimmingStartsOnlyNearTheCeiling() {
        // 契约是「不超过目标 10%」，所以 10% 以内（含）都算合格，不必为此再花一次模型调用。
        // 以前是超 4% 就动手，等于把大量完全可交付的稿子又重写一遍 —— 每章十几秒到两分钟，
        // 三章叠加能把整篇拖过四分钟，前端先断连。
        ThesisFullTextGenerator.WordBudget under = new ThesisFullTextGenerator.WordBudget(8000, 6);
        under.spend(8600); // +7.5%：还在契约内
        assertThat(under.shouldTrim()).isFalse();

        ThesisFullTextGenerator.WordBudget near = new ThesisFullTextGenerator.WordBudget(8000, 6);
        near.spend(8700); // +8.75%：仍在线内，留给「压缩本身可能不收敛」的余量
        assertThat(near.shouldTrim()).isFalse();

        ThesisFullTextGenerator.WordBudget atCeiling = new ThesisFullTextGenerator.WordBudget(8000, 6);
        atCeiling.spend(8800); // 正好 110%：已经没有余量，该收了
        assertThat(atCeiling.shouldTrim()).isTrue();

        ThesisFullTextGenerator.WordBudget over = new ThesisFullTextGenerator.WordBudget(8000, 6);
        over.spend(8900); // 越过 110%
        assertThat(over.shouldTrim()).isTrue();
    }

    @Test
    void countWordsIgnoresWhitespaceOnly() {
        // 中文按去空白后的字符数计，段落空行不应该被算成字数
        assertThat(ThesisFullTextGenerator.countWords("本研究  提出\n\n一种方法。")).isEqualTo(10);
        assertThat(ThesisFullTextGenerator.countWords(null)).isZero();
    }

    @Test
    void smallTargetsStillGetAWorkableChapterRange() {
        ThesisFullTextGenerator.WordBudget budget = new ThesisFullTextGenerator.WordBudget(1000, 5);
        ThesisFullTextGenerator.WordBudget.Range range = budget.next();

        assertThat(range.min()).isGreaterThanOrEqualTo(400);
        assertThat(range.max()).isGreaterThan(range.min());
    }

    @Test
    void hardTrimDropsWholeSentencesOnly() {
        String body = "第一句话说明背景。第二句话交代方法。第三句话给出结论。";

        String cut = ThesisFullTextGenerator.cutToSentenceBoundary(body, 12);

        // 保留整句，不能出现「第二句话交代方」这种半句
        assertThat(cut).isEqualTo("第一句话说明背景。");
        assertThat(cut).endsWith("。");
    }

    @Test
    void hardTrimLeavesTextAlreadyInsideTheLimitUntouched() {
        String body = "短正文。";
        assertThat(ThesisFullTextGenerator.cutToSentenceBoundary(body, 100)).isEqualTo(body);
    }
}
