package com.hub.backend.modules.thesis;

import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeRequest;
import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the outline engine: no Spring context, no database.
 */
class ThesisOutlineEngineTest {

    private final ThesisOutlineGenerator generator = new ThesisOutlineGenerator();
    private final ThesisOutlineNormalizer normalizer = new ThesisOutlineNormalizer();
    private final ThesisTopicOptimizer optimizer = new ThesisTopicOptimizer();

    @Test
    void buildsAThreeLevelTreeWithFiveChapters() {
        List<ThesisOutlineNode> tree = generator.build("UNDERGRAD", "工学", "软件工程",
                "基于深度学习的短视频推荐算法优化", false);

        assertThat(tree).hasSize(7); // 五章 + 参考文献 + 致谢
        assertThat(tree.get(0).level()).isEqualTo(1);
        assertThat(tree.get(0).children()).isNotEmpty();
        assertThat(tree.get(0).children().get(0).level()).isEqualTo(2);
        assertThat(tree.get(0).children().get(0).children()).isNotEmpty();
        assertThat(tree.get(0).children().get(0).children().get(0).level()).isEqualTo(3);
        assertThat(depth(tree)).isEqualTo(3);
    }

    @Test
    void chapterThreeFollowsTheTier() {
        String undergrad = firstChapterThree("UNDERGRAD");
        String master = firstChapterThree("MASTER");
        String journal = firstChapterThree("JOURNAL");
        String proposal = firstChapterThree("PROPOSAL");

        assertThat(undergrad).contains("系统设计");
        assertThat(master).contains("研究假设");
        assertThat(journal).contains("识别策略");
        assertThat(proposal).contains("研究内容");
        assertThat(List.of(undergrad, master, journal, proposal)).doesNotHaveDuplicates();
    }

    @Test
    void dataModelFlagAppendsACodeAppendix() {
        List<ThesisOutlineNode> plain = generator.build("UNDERGRAD", "论文助手", "工学", "软件工程",
                "选题", false, 3);
        List<ThesisOutlineNode> withData = generator.build("UNDERGRAD", "论文助手", "工学", "软件工程",
                "选题", true, 3);

        assertThat(withData).hasSize(plain.size() + 1);
        // 附录排在参考文献之前，参考文献 / 致谢 仍是文档的最后两节
        ThesisOutlineNode appendix = withData.get(withData.size() - 3);
        assertThat(appendix.title()).contains("附录").contains("数据模型").contains("代码");
        assertThat(appendix.children()).extracting(ThesisOutlineNode::title)
                .containsExactly("A.1 数据模型设计", "A.2 关键代码实现");
        assertThat(withData.get(withData.size() - 2).title()).isEqualTo("参考文献");
        assertThat(withData.get(withData.size() - 1).title()).isEqualTo("致谢");
    }

    @Test
    void dataModelAppendixIsAlsoAddedToPaperTypesWithTheirOwnSkeleton() {
        // 开关与文本类型是两个维度：类型自带骨架时，之前 withData 被完全忽略，
        // 勾选「数据模型与代码」对 12 种类型没有任何效果。
        for (PaperTypeProfile profile : PaperTypeProfile.values()) {
            List<ThesisOutlineNode> tree = generator.build("UNDERGRAD", profile.label(), "工学",
                    "软件工程", "在线教学平台设计", true, 3);
            assertThat(tree).as(profile.label())
                    .anyMatch(node -> node.title().contains("附录") && node.title().contains("代码"));
            // 参考文献（部分骨架还有致谢）仍然是最后的内容
            assertThat(tree.get(tree.size() - 1).title()).as(profile.label()).containsAnyOf("参考文献", "致谢");
        }
    }

    @Test
    void depthTwoDropsEveryThirdLevelHeading() {
        List<ThesisOutlineNode> two = generator.build("UNDERGRAD", "工学", "软件工程", "选题", false, 2);
        List<ThesisOutlineNode> three = generator.build("UNDERGRAD", "工学", "软件工程", "选题", false, 3);

        assertThat(depth(two)).isEqualTo(2);
        assertThat(depth(three)).isEqualTo(3);
        assertThat(count(two)).isLessThan(count(three));
        // 章和节都在，只是节不再展开
        assertThat(two.get(0).children()).isNotEmpty();
        assertThat(two.get(0).children().get(0).children()).isEmpty();
    }

    @Test
    void doctoralTierHasAFourthSectionInChapterThree() {
        List<ThesisOutlineNode> doctor = generator.build("DOCTOR", "管理学", "工商管理", "选题", false, 3);
        ThesisOutlineNode chapterThree = doctor.stream()
                .filter(n -> n.title().startsWith("第三章"))
                .findFirst()
                .orElseThrow();

        assertThat(chapterThree.children()).hasSize(4);
        assertThat(chapterThree.children().get(3).title()).contains("机制检验");
    }

    @Test
    void catalogMapsEducationLevelsOntoTemplates() {
        // 只有映射逻辑被测，模型仓库传 null：这个测试不碰目录列表
        ThesisCatalogService catalog = new ThesisCatalogService(null);

        assertThat(catalog.tierForEducation("专科在读")).isEqualTo("UNDERGRAD");
        assertThat(catalog.tierForEducation("硕士毕业")).isEqualTo("MASTER");
        assertThat(catalog.tierForEducation("博士在读")).isEqualTo("DOCTOR");
        assertThat(catalog.tierForEducation("未知学历")).isEqualTo("UNDERGRAD");
        assertThat(catalog.defaultDepth("专科在读")).isEqualTo(2);
        assertThat(catalog.defaultDepth("本科在读")).isEqualTo(3);
        assertThat(catalog.isPostgraduate("硕士在读")).isTrue();
        assertThat(catalog.isPostgraduate("本科在读")).isFalse();
    }

    @Test
    void nodeIdsAreUnique() {
        List<ThesisOutlineNode> tree = generator.build("MASTER", "管理学", "工商管理", "选题", false);
        List<String> ids = flatten(tree).stream().map(ThesisOutlineNode::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void normalizerCorrectsDepthAndFillsMissingIds() {
        ThesisOutlineNode bad = new ThesisOutlineNode(null, "  研究   背景  ", 3, null);
        List<ThesisOutlineNode> fixed = normalizer.normalize(List.of(bad));

        assertThat(fixed).hasSize(1);
        assertThat(fixed.get(0).id()).isNotBlank();
        assertThat(fixed.get(0).title()).isEqualTo("研究 背景");
        assertThat(fixed.get(0).level()).isEqualTo(1);
        assertThat(fixed.get(0).children()).isEmpty();
    }

    @Test
    void normalizerRepairsDuplicateIds() {
        ThesisOutlineNode a = new ThesisOutlineNode("dup", "第一章", 1, List.of());
        ThesisOutlineNode b = new ThesisOutlineNode("dup", "第二章", 1, List.of());
        List<ThesisOutlineNode> fixed = normalizer.normalize(List.of(a, b));

        assertThat(fixed.get(0).id()).isNotEqualTo(fixed.get(1).id());
    }

    @Test
    void normalizerRejectsBlankTitles() {
        ThesisOutlineNode blank = new ThesisOutlineNode("x", "   ", 1, List.of());
        assertThatThrownBy(() -> normalizer.normalize(List.of(blank)))
                .hasMessageContaining("must not be blank");
    }

    @Test
    void optimizerRewritesATechnicalTopic() {
        ThesisTopicOptimizeResponse result = optimizer.optimize(
                new ThesisTopicOptimizeRequest("短视频推荐算法优化", "计算机科学与技术"));

        assertThat(result.optimized()).contains("短视频推荐算法优化");
        assertThat(result.optimized()).contains("研究");
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void optimizerNeverEchoesTheInputBack() {
        String topic = "基于深度学习的短视频推荐算法优化研究";
        ThesisTopicOptimizeResponse result = optimizer.optimize(new ThesisTopicOptimizeRequest(topic, "软件工程"));
        assertThat(result.optimized()).isNotEqualTo(topic);
    }

    @Test
    void optimizerGivesASeedTopicWhenTheInputIsEmpty() {
        ThesisTopicOptimizeResponse result = optimizer.optimize(new ThesisTopicOptimizeRequest("", "金融学"));
        assertThat(result.optimized()).isNotBlank();
        assertThat(result.optimized().length()).isGreaterThan(10);
    }

    private String firstChapterThree(String tier) {
        return generator.build(tier, "工学", "软件工程", "选题", false).stream()
                .filter(n -> n.title().startsWith("第三章"))
                .findFirst()
                .orElseThrow()
                .title();
    }

    @Test
    void paperTypeChangesTheSkeletonWithinTheSameTier() {
        // 同属 UNDERGRAD，但「教学设计」是教案体例、「实训报告」是过程记录体例，
        // 两者不能都长成「系统设计与实现」的学位论文骨架。
        List<ThesisOutlineNode> thesis = generator.build("UNDERGRAD", "论文助手", "工学", "软件工程",
                "在线教学平台设计", false, 3);
        List<ThesisOutlineNode> design = generator.build("UNDERGRAD", "教学设计", "工学", "软件工程",
                "在线教学平台设计", false, 3);
        List<ThesisOutlineNode> training = generator.build("UNDERGRAD", "实训报告", "工学", "软件工程",
                "在线教学平台设计", false, 3);

        assertThat(titles(design)).anyMatch(t -> t.contains("教学目标与重难点"));
        assertThat(titles(design)).anyMatch(t -> t.contains("教学过程设计"));
        assertThat(titles(training)).anyMatch(t -> t.contains("实训内容与过程记录"));
        assertThat(titles(training)).anyMatch(t -> t.contains("问题与解决过程"));
        assertThat(titles(design)).isNotEqualTo(titles(thesis));
        assertThat(titles(training)).isNotEqualTo(titles(thesis));
        assertThat(titles(design)).isNotEqualTo(titles(training));
    }

    @Test
    void everyPaperTypeProducesItsOwnDistinctOutline() {
        // 所有类型都有专属骨架，所以在同一学历层次下也必须彼此不同 —— 否则「选择文本类型」
        // 对生成结果就是无效操作。
        List<String> signatures = java.util.Arrays.stream(PaperTypeProfile.values())
                .map(profile -> String.join("|",
                        titles(generator.build("UNDERGRAD", profile.label(), "工学", "软件工程",
                                "在线教学平台设计", false, 3))))
                .toList();

        for (PaperTypeProfile profile : PaperTypeProfile.values()) {
            List<ThesisOutlineNode> tree = generator.build("UNDERGRAD", profile.label(),
                    "工学", "软件工程", "在线教学平台设计", false, 3);
            assertThat(tree).as(profile.label()).hasSizeGreaterThanOrEqualTo(3);
        }
        assertThat(signatures).doesNotHaveDuplicates();
    }

    @Test
    void unknownOrBlankPaperTypeFallsBackToTheDegreeSkeleton() {
        List<String> blank = titles(generator.build("UNDERGRAD", null, "工学", "软件工程",
                "在线教学平台设计", false, 3));
        List<String> unknown = titles(generator.build("UNDERGRAD", "不存在的类型", "工学", "软件工程",
                "在线教学平台设计", false, 3));
        List<String> thesis = titles(generator.build("UNDERGRAD", "论文助手", "工学", "软件工程",
                "在线教学平台设计", false, 3));

        assertThat(blank).isEqualTo(thesis);
        assertThat(unknown).isEqualTo(thesis);
    }

    @Test
    void everyPaperTypeExposesKeywordHints() {
        for (PaperTypeProfile profile : PaperTypeProfile.values()) {
            assertThat(profile.keywords())
                    .as(profile.label() + " 的关键字提示")
                    .hasSizeGreaterThanOrEqualTo(4);
            assertThat(profile.outlineGuide()).as(profile.label() + " 的大纲指引").isNotBlank();
            assertThat(profile.writingGuide()).as(profile.label() + " 的写作指引").isNotBlank();
        }
    }

    private static List<String> titles(List<ThesisOutlineNode> nodes) {
        return flatten(nodes).stream().map(ThesisOutlineNode::title).toList();
    }

    private static int depth(List<ThesisOutlineNode> nodes) {
        int max = 0;
        for (ThesisOutlineNode node : nodes) {
            max = Math.max(max, 1 + depth(node.children()));
        }
        return max;
    }

    private static int count(List<ThesisOutlineNode> nodes) {
        return nodes.stream().mapToInt(n -> 1 + count(n.children())).sum();
    }

    private static List<ThesisOutlineNode> flatten(List<ThesisOutlineNode> nodes) {
        return nodes.stream()
                .flatMap(n -> {
                    List<ThesisOutlineNode> all = new java.util.ArrayList<>();
                    all.add(n);
                    all.addAll(flatten(n.children()));
                    return all.stream();
                })
                .toList();
    }
}
