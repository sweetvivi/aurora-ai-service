package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference lookup parsing and body normalisation.
 *
 * <p>The network is not touched: {@code parse} takes the JSON body directly, which is the part that
 * decides what ends up in a citation list. That is deliberate — a test that hits OpenAlex fails
 * whenever the index is slow, and it would not tell us anything about our own filtering.</p>
 */
class ThesisReferenceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ThesisReferenceService service() {
        // Every argument is inert for parse(): no HTTP client is used when parsing a body.
        return new ThesisReferenceService(objectMapper, true, "https://example.invalid", "", 1000);
    }

    @Test
    void keepsOnlyWorksWithADoiAndATitle() throws Exception {
        String body = """
                {"results":[
                  {"doi":"https://doi.org/10.1/abc","title":"大语言模型的金融应用","publication_year":2024,
                   "authorships":[{"author":{"display_name":"张三"}},{"author":{"display_name":"李四"}}],
                   "primary_location":{"source":{"display_name":"金融研究"}}},
                  {"doi":null,"title":"没有 DOI 的条目","publication_year":2023,"authorships":[]},
                  {"doi":"https://doi.org/10.2/def","title":null,"publication_year":2023,"authorships":[]}
                ]}
                """;

        List<ThesisReferenceService.Reference> parsed = service().parse(body, 10);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).doi()).isEqualTo("https://doi.org/10.1/abc");
        assertThat(parsed.get(0).source()).isEqualTo("金融研究");
        assertThat(parsed.get(0).year()).isEqualTo(2024);
        // 作者最多取三人，用顿号连接，符合中文参考文献习惯
        assertThat(parsed.get(0).authors()).isEqualTo("张三、李四");
    }

    @Test
    void dropsDuplicateTitlesEvenWhenDoisDiffer() throws Exception {
        String body = """
                {"results":[
                  {"doi":"https://doi.org/10.1/a","title":"同名条目","publication_year":2024,"authorships":[]},
                  {"doi":"https://doi.org/10.1/b","title":"同名条目","publication_year":2023,"authorships":[]}
                ]}
                """;

        assertThat(service().parse(body, 10)).hasSize(1);
    }

    @Test
    void respectsTheRequestedCount() throws Exception {
        StringBuilder body = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"doi\":\"https://doi.org/10.1/").append(i).append("\",\"title\":\"条目")
                    .append(i).append("\",\"publication_year\":2024,\"authorships\":[]}");
        }
        body.append("]}");

        assertThat(service().parse(body.toString(), 3)).hasSize(3);
    }

    @Test
    void citationFallsBackWhenFieldsAreMissing() {
        ThesisReferenceService.Reference bare =
                new ThesisReferenceService.Reference("题目", null, null, null, "https://doi.org/10.9/x");

        // 缺年份时不能印出「n.d..」这种双句点，也不能把 n.d. 当成有效年份
        assertThat(bare.citation()).isEqualTo("佚名．题目[J]．未知来源． DOI: https://doi.org/10.9/x");
    }

    @Test
    void chineseCitationUsesSurnameFirstAndChinesePunctuation() {
        ThesisReferenceService.Reference reference = new ThesisReferenceService.Reference(
                "基于大语言模型的金融舆情分析", "马钰、李志飞", "金融研究", 2024, "https://doi.org/10.1/x");

        assertThat(reference.citation())
                .isEqualTo("马钰、李志飞．基于大语言模型的金融舆情分析[J]．金融研究，2024． DOI: https://doi.org/10.1/x");
        // 中文条目里不该出现半角逗号拼接的「, 2024」
        assertThat(reference.citation()).doesNotContain(", 2024");
    }

    @Test
    void englishCitationKeepsHalfWidthPunctuation() {
        ThesisReferenceService.Reference reference = new ThesisReferenceService.Reference(
                "A survey of sentiment analysis", "Ming Liu", "IEEE Access", 2023, "https://doi.org/10.2/y");

        assertThat(reference.citation())
                .isEqualTo("Ming Liu. A survey of sentiment analysis[J]. IEEE Access, 2023. DOI: https://doi.org/10.2/y");
    }

    /**
     * 真实数据回归：OpenAlex 把中文名存成「名 姓」（钰琪 杨），直接打印出来就是用户反馈的
     * 「钰琪 杨、青 严、晔 韩」。这是交付物硬伤，不是排版口味问题。
     */
    @Test
    void swapsGivenFamilyOrderForChineseNames() throws Exception {
        String body = """
                {"results":[
                  {"doi":"https://doi.org/10.1/abc","title":"金融舆情分析","publication_year":2024,
                   "authorships":[
                     {"author":{"display_name":"钰琪 杨"},"raw_author_name":"钰琪 杨"},
                     {"author":{"display_name":"青 严"},"raw_author_name":"青 严"},
                     {"author":{"display_name":"晔 韩"},"raw_author_name":"晔 韩"}],
                   "primary_location":{"source":{"display_name":"金融研究"}}}
                ]}
                """;

        List<ThesisReferenceService.Reference> parsed = service().parse(body, 10);

        assertThat(parsed.get(0).authors()).isEqualTo("杨钰琪、严青、韩晔");
    }

    @Test
    void prefersTheCommaSplitFromRawAuthorName() {
        // raw 里带逗号就是作者本人给的切分，比 display_name 的空格可靠
        assertThat(ThesisReferenceService.normaliseAuthorName("徐, 远航", "远航 徐")).isEqualTo("徐远航");
        assertThat(ThesisReferenceService.normaliseAuthorName("Liu, Ming", "Ming Liu")).isEqualTo("Liu Ming");
    }

    @Test
    void keepsNamesThatAreAlreadySurnameFirst() {
        // 没有空格的中文名本身已完整，不能再动
        assertThat(ThesisReferenceService.normaliseAuthorName(null, "张淼")).isEqualTo("张淼");
        // 西文名保持原样，重排会把同一个 bug 反向再犯一次
        assertThat(ThesisReferenceService.normaliseAuthorName(null, "Ming Liu")).isEqualTo("Ming Liu");
    }

    @Test
    void usesTheChineseFormWhenOpenAlexKeepsItInBrackets() {
        assertThat(ThesisReferenceService.normaliseAuthorName("Yuchen (蔡雨晨) Cai", "Yuchen (蔡雨晨) Cai"))
                .isEqualTo("蔡雨晨");
    }

    @Test
    void addsDengWhenThereAreMoreThanThreeAuthors() throws Exception {
        String body = """
                {"results":[
                  {"doi":"https://doi.org/10.1/many","title":"多作者论文","publication_year":2024,
                   "authorships":[
                     {"author":{"display_name":"甲 王"}},
                     {"author":{"display_name":"乙 李"}},
                     {"author":{"display_name":"丙 张"}},
                     {"author":{"display_name":"丁 赵"}}]}
                ]}
                """;

        List<ThesisReferenceService.Reference> parsed = service().parse(body, 10);

        // GB/T 7714：超过三位列前三并加「等」，否则读者会以为只有三位作者
        assertThat(parsed.get(0).authors()).isEqualTo("王甲、李乙、张丙等");
    }

    /**
     * 库里已存的老全文不会因为改生成器而变好 —— 引用文本是生成时拼好的字符串。
     * 读回时修一次，用户才不用为了一个格式 bug 重新花钱生成整篇。
     */
    @Test
    void repairsAuthorOrderInLegacyStoredCitations() {
        assertThat(ThesisReferenceService.repairLegacyCitation(
                "钰琪 杨、青 严、晔 韩. 基于情感分析的舆情监测[J]. 计算机应用, 2024. DOI: https://doi.org/10.1/x"))
                .isEqualTo("杨钰琪、严青、韩晔. 基于情感分析的舆情监测[J]. 计算机应用, 2024. DOI: https://doi.org/10.1/x");

        // 老数据可能有半角「. 」分隔，也要认得出作者段到哪结束
        assertThat(ThesisReferenceService.repairLegacyCitation(
                "广秋 陈、玥 武、侨 吴. 论文题目[J]. 某个期刊, 2023. DOI: https://doi.org/10.1/z"))
                .startsWith("陈广秋、武玥、吴侨.");
    }

    @Test
    void citationRepairIsIdempotent() {
        String alreadyRight = "马钰．基于大语言模型的金融舆情分析[J]．金融研究，2024． DOI: https://doi.org/10.1/x";
        // 已经是姓在前，再修一次必须原样返回，否则每次读都在改数据
        assertThat(ThesisReferenceService.repairLegacyCitation(alreadyRight)).isEqualTo(alreadyRight);
        assertThat(ThesisReferenceService.repairLegacyCitation(
                ThesisReferenceService.repairLegacyCitation(
                        "钰琪 杨. 题目[J]. 期刊, 2024. DOI: https://doi.org/10.1/x")))
                .isEqualTo("杨钰琪. 题目[J]. 期刊, 2024. DOI: https://doi.org/10.1/x");
    }

    @Test
    void citationRepairLeavesEnglishEntriesAlone() {
        String english = "Ming Liu. A survey[J]. IEEE Access, 2023. DOI: https://doi.org/10.2/y";
        // 西文条目作者本来名在前，修了就是反向的同一个 bug
        assertThat(ThesisReferenceService.repairLegacyCitation(english)).isEqualTo(english);
        assertThat(ThesisReferenceService.repairLegacyCitation(null)).isNull();
        assertThat(ThesisReferenceService.repairLegacyCitation("")).isEmpty();
    }

    /**
     * 作者段的边界必须切在**第一个**分隔符上。
     *
     * <p>库里的老条目形如「作者. 标题[J]. 期刊, 2024. DOI: …」，期刊为空时还有
     * 「未知来源, 2024」。如果按「最后一个 . 」或按逗号切，会把标题甚至期刊名当成作者
     * 的一部分去交换顺序，那比原来的 bug 更糟 —— 名字本来是错的，现在连标题都被改了。</p>
     */
    @Test
    void citationRepairSplitsOnTheFirstSeparatorOnly() {
        // 标题里含半角句点，不能把标题后半段当成作者
        assertThat(ThesisReferenceService.repairLegacyCitation(
                "钰琪 杨. 基于BERT. 模型的情感分析[J]. 计算机应用, 2024. DOI: https://doi.org/10.1/x"))
                .isEqualTo("杨钰琪. 基于BERT. 模型的情感分析[J]. 计算机应用, 2024. DOI: https://doi.org/10.1/x");

        // 期刊为空的兜底条目：作者段之后还有逗号，不该被卷进作者
        assertThat(ThesisReferenceService.repairLegacyCitation(
                "钰琪 杨. 某个题目[J]. 未知来源, 2024. DOI: https://doi.org/10.1/y"))
                .isEqualTo("杨钰琪. 某个题目[J]. 未知来源, 2024. DOI: https://doi.org/10.1/y");

        // 单字姓 + 双字名（真实数据里「晔 韩」「霞 米」这种）
        assertThat(ThesisReferenceService.repairLegacyCitation(
                "霞 米. 题目[J]. 期刊, 2024. DOI: https://doi.org/10.1/z"))
                .startsWith("米霞.");
        assertThat(ThesisReferenceService.repairLegacyCitation(
                "亚萍 王. 题目[J]. 期刊, 2024. DOI: https://doi.org/10.1/w"))
                .startsWith("王亚萍.");
    }

    @Test
    void relevanceFilterDropsOffTopicWorks() {
        // 真实发生过：检索「金融舆情 + 计算机」，混进来一篇室内风水设计。
        List<ThesisReferenceService.Reference> candidates = List.of(
                new ThesisReferenceService.Reference("基于大语言模型和RAG的舆情知识交互研究", "马钰", "人文与社会科学学刊", 2025, "d1"),
                new ThesisReferenceService.Reference("基于虚拟实境以罗经仪厘测室内风水设计对心理之解析", "罗某", "中国科学与技术学报", 2025, "d2"));

        List<ThesisReferenceService.Reference> kept =
                service().rankByRelevance(candidates, "金融舆情 大语言模型", 10);

        assertThat(kept).extracting(ThesisReferenceService.Reference::doi).containsExactly("d1");
    }

    @Test
    void papersCoveringMoreTopicWordsRankFirst() {
        // 真实发生过：逐词检索「大语言模型」带回来一堆英语教学论文，排在真正相关的文献前面。
        List<ThesisReferenceService.Reference> candidates = List.of(
                new ThesisReferenceService.Reference("大语言模型在英语写作教学中的应用", "甲", "外语界", 2025, "english"),
                new ThesisReferenceService.Reference("大语言模型驱动的金融舆情监测方法研究", "乙", "金融研究", 2025, "fin"));

        List<ThesisReferenceService.Reference> kept =
                service().rankByRelevance(candidates, "金融舆情 大语言模型", 10);

        assertThat(kept.get(0).doi()).isEqualTo("fin");
    }

    @Test
    void fragmentsSplitCjkIntoBigramsAndKeepLatinTokens() {
        assertThat(ThesisReferenceService.fragments("金融舆情 RAG"))
                .contains("金融", "融舆", "舆情", "RAG")
                // 单字太宽：命中「金」就等于命中一切中文文献
                .doesNotContain("金");

        assertThat(ThesisReferenceService.phrases("金融舆情 大语言模型"))
                .contains("金融舆情", "大语言模型", "金融舆情大语言模型");
    }

    /**
     * 真实数据回归：一条 {@code raw_author_name} 里塞了整串作者，用全角逗号分隔。
     *
     * <p>线上出现过「闫屹伟，李昕明，朱志刚范子杨」—— 逗号留在文中、名字黏成一团，
     * 读者第一反应是文献被写坏了。这种串必须在拆分时就把逗号当分隔符。</p>
     */
    @Test
    void splitsAuthorListsPackedIntoOneRawName() throws Exception {
        // 「范子杨 闫屹伟」是两个人（末词三字不可能是姓），各自本来就是「姓 名」顺序
        assertThat(ThesisReferenceService.normaliseAuthorship("范子杨 闫屹伟，李昕明，朱志刚", null))
                .containsExactly("范子杨", "闫屹伟", "李昕明", "朱志刚");

        String body = """
                {"results":[
                  {"doi":"https://doi.org/10.1/packed","title":"智能手语翻译","publication_year":2025,
                   "authorships":[
                     {"raw_author_name":"范子杨 闫屹伟，李昕明，朱志刚",
                      "author":{"display_name":"李昕明 范子杨 闫屹伟"}}]}
                ]}
                """;

        ThesisReferenceService.Reference parsed = service().parse(body, 10).get(0);

        // 四位作者：列前三 + 等，且作者之间必须是顿号而不是全角逗号
        assertThat(parsed.authors()).isEqualTo("范子杨、闫屹伟、李昕明等");
        assertThat(parsed.authors()).doesNotContain("，");
    }

    @Test
    void referenceQuerySplitsProgrammeNamesIntoSubjects() {
        assertThat(ThesisService.referenceQuery("选题", "计算机科学与技术", "工学")).contains("计算机");
        assertThat(ThesisService.referenceQuery("选题", "工商管理", "管理学")).contains("工商管理");
        assertThat(ThesisService.referenceQuery("选题", "软件工程", "工学")).contains("软件");
    }

    @Test
    void referenceQueryStripsAcademicBoilerplate() {
        String query = ThesisService.referenceQuery("基于大语言模型的金融舆情分析系统设计与实现", "计算机科学与技术", "工学");

        assertThat(query).doesNotContain("基于").doesNotContain("设计与实现").doesNotContain("研究");
        // 「分析系统」这类后缀留下来会把查询变成一整句，命中率暴跌
        assertThat(query).doesNotContain("分析系统").doesNotContain("系统设计");
        assertThat(query).contains("金融舆情");
        // 专业名整体检索不到任何东西：拆成有区分度的词，并丢掉「科学」「技术」这类泛化词
        assertThat(query).contains("计算机").doesNotContain("计算机科学与技术");
        assertThat(query).doesNotContain("计算机科学");
    }

    @Test
    void disabledServiceReturnsNothing() {
        ThesisReferenceService off =
                new ThesisReferenceService(objectMapper, false, "https://example.invalid", "", 1000);

        assertThat(off.enabled()).isFalse();
        assertThat(off.search("任意题目", 5)).isEmpty();
    }

    @Test
    void bodyNormalisationStripsFencesAndRepeatedHeading() {
        String raw = """
                ```markdown
                第一章 绪论

                研究背景是本课题的出发点。

                数字经济正在重塑金融业。
                ```
                """;

        String cleaned = ThesisFullTextGenerator.normaliseBody(raw, "第一章 绪论");

        assertThat(cleaned).startsWith("研究背景是本课题的出发点");
        assertThat(cleaned).doesNotContain("```");
        assertThat(cleaned).doesNotContain("第一章 绪论");
    }

    @Test
    void bodyNormalisationKeepsNumberedSubheadings() {
        String raw = "1.1 研究背景\n\n第一段内容。\n\n1.2 研究意义\n\n第二段内容。";

        String cleaned = ThesisFullTextGenerator.normaliseBody(raw, "第一章 绪论");

        // 小节编号是正文的一部分，不能被当成重复标题删掉
        assertThat(cleaned).contains("1.1 研究背景");
        assertThat(cleaned).contains("1.2 研究意义");
    }
}
