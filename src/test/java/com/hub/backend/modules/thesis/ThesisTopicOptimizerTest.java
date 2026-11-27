package com.hub.backend.modules.thesis;

import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeRequest;
import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题目优化：模板拼接不能把题目撑破 60 字上限，也不能拼出重复词与半截词。
 *
 * <p>这组断言全部来自真实踩到的输出：</p>
 * <ul>
 *   <li>「基于深度学习与多模态融合的**深度学习的**短视频推荐算法优化研究」—— 定语与题目重复；</li>
 *   <li>「…多目标智能调度算法优化**及其在移**研究：机理、模型与实证检验」—— {@code substring(0,
 *       32)} 把「及其在移动端」切成半截。</li>
 * </ul>
 *
 * <p>上限 60 不是随手取的：{@code ThesisOutlineRequest.topic} 上有 {@code @Size(max = 60)}，
 * 优化出来的题目提交建稿时会被 400 拦掉，用户点完「AI 优化题目」反而不能提交。</p>
 */
class ThesisTopicOptimizerTest {

    private final ThesisTopicOptimizer optimizer = new ThesisTopicOptimizer();

    private ThesisTopicOptimizeResponse optimize(String topic) {
        return optimizer.optimize(new ThesisTopicOptimizeRequest(topic, "计算机科学与技术"));
    }

    @Test
    void doesNotRepeatTheQualifierTheTopicAlreadyCarries() {
        String optimized = optimize("基于深度学习的短视频推荐算法优化研究").optimized();

        // 题目里已经有「深度学习」，模板就不该再前置一遍「深度学习与多模态融合的」
        assertThat(optimized).doesNotContain("融合的深度学习的");
        assertThat(optimized).startsWith("基于深度学习的短视频推荐算法优化");
    }

    @Test
    void neverExceedsTheSixtyCharacterLimitOfTheCreateEndpoint() {
        String longTopic = "面向高校实验室设备预约与冲突检测的多目标智能调度算法优化及其在移动端与小程序端的工程化实现与性能评估";
        String optimized = optimize(longTopic).optimized();

        assertThat(optimized.length()).isLessThanOrEqualTo(60);
    }

    @Test
    void cutsOnAMeaningBoundaryInsteadOfMidWord() {
        String longTopic = "面向高校实验室设备预约与冲突检测的多目标智能调度算法优化及其在移动端与小程序端的工程化实现与性能评估";
        String optimized = optimize(longTopic).optimized();

        // 断点优先落在「及其」处，而不是「与」处留下半截定语
        assertThat(optimized).contains("多目标智能调度算法优化研究");
        // 硬截的痕迹：句中不该出现「移动端研究」这种被腰斩后又接上后缀的形态
        assertThat(optimized).doesNotContain("在移动端研究");
    }

    @Test
    void blankTopicFallsBackToTheSeedTitle() {
        ThesisTopicOptimizeResponse response = optimize("  ");

        assertThat(response.optimized()).isNotBlank();
        assertThat(response.optimized().length()).isLessThanOrEqualTo(60);
    }

    @Test
    void keepsTheTopicIntactWhenItIsShort() {
        String optimized = optimize("学院管理系统").optimized();

        // 「系统」属于 TRAILING_FILLER（通用名词），会被剥掉 —— 这里只锁住主体词没被削
        assertThat(optimized).contains("学院管理");
        assertThat(optimized.length()).isLessThanOrEqualTo(60);
    }
}
