package com.hub.backend.modules.thesis;

import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the three level outline for a topic.
 *
 * <p>This is a deterministic template engine, not a model call: the same (tier, discipline, major,
 * topic) always yields the same tree, which keeps the "free outline" step instant and repeatable,
 * and makes the editor's diff view meaningful. The wording of the third level headings is the only
 * part that depends on the topic, so the chapter skeleton stays recognisable as a Chinese thesis
 * outline while the branch specific chapter (design / empirical / identification) follows the tier.
 * A model backed variant can replace {@link #build} later without touching the controller.</p>
 */
@Service
public class ThesisOutlineGenerator {

    /** A placeholder replaced by the real topic when a heading is rendered. */
    private static final String TOPIC_SLOT = "{topic}";
    private static final String MAJOR_SLOT = "{major}";
    private static final String DISCIPLINE_SLOT = "{discipline}";

    private final AtomicLong sequence = new AtomicLong();

    /**
     * @param tier       one of UNDERGRAD / MASTER / DOCTOR / JOURNAL / PROPOSAL
     * @param discipline first level discipline, only used for wording
     * @param major      second level major, only used for wording
     * @param topic      thesis title
     * @param withData   when true an appendix with the data model and the key code is appended
     * @param depth      2 drops every third level heading, 3 keeps the full tree
     */
    public List<ThesisOutlineNode> build(String tier, String discipline, String major, String topic,
                                         boolean withData, int depth) {
        return build(tier, null, discipline, major, topic, withData, depth);
    }

    /**
     * Same engine, but the chapter skeleton follows the paper type when that type defines one.
     *
     * <p>Without this every type inside a tier produced byte-identical outlines — 「教学设计」and
     * 「实训报告」both came out as a 系统设计与实现 thesis. The tier default is still the fallback,
     * so types whose shape genuinely matches the degree template (论文助手 / 期刊文章 / 开题报告 /
     * 研究生小文章) keep the structure they were tuned against.</p>
     *
     * @param paperType display label from the catalog; unknown or blank falls back to the tier
     */
    public List<ThesisOutlineNode> build(String tier, String paperType, String discipline,
                                         String major, String topic, boolean withData, int depth) {
        String paperSkeleton = PaperTypeProfile.byLabel(paperType).skeleton();
        String base = paperSkeleton != null ? paperSkeleton : skeleton(tier);
        // 增值项与文本类型正交：类型决定「这篇东西是什么」，数据模型与代码是用户额外勾选的一块内容。
        // 之前只有学历默认骨架里嵌了一个 3.3.3，类型自带骨架的那 12 种情况开关等于没接。
        String spec = (withData ? appendDataAppendix(base) : base)
                .replace(TOPIC_SLOT, shorten(topic))
                .replace(MAJOR_SLOT, major)
                .replace(DISCIPLINE_SLOT, discipline);
        List<ThesisOutlineNode> tree = parse(spec);
        return depth <= 2 ? toTwoLevel(tree) : tree;
    }

    /**
     * Appends the data-model/code appendix before the back matter.
     *
     * <p>An appendix rather than a numbered chapter: every type numbers its chapters differently
     * (five to six, ending with 结论), so inserting a numbered chapter would either collide with
     * 「结论」or force every skeleton to be renumbered. An appendix keeps its own A.x numbering and
     * reads exactly the way a real thesis presents table definitions and code listings.</p>
     */
    private static String appendDataAppendix(String spec) {
        int backMatter = spec.indexOf("# 参考文献");
        return backMatter < 0
                ? spec + DATA_APPENDIX
                : spec.substring(0, backMatter) + DATA_APPENDIX + spec.substring(backMatter);
    }

    /**
     * The appendix nodes on their own.
     *
     * <p>Used by the service to guarantee the 「数据模型与代码」 switch is honoured even when the
     * model wrote the outline: the model is asked for the appendix in its prompt, but a prompt is
     * a request, not a contract. Each call yields fresh node ids.</p>
     */
    public List<ThesisOutlineNode> dataAppendix() {
        return parse(DATA_APPENDIX);
    }

    /** Convenience overload for callers that always want the full three level tree. */
    public List<ThesisOutlineNode> build(String tier, String discipline, String major, String topic,
                                         boolean withData) {
        return build(tier, discipline, major, topic, withData, 3);
    }

    /**
     * Keeps chapters and sections, drops the third level.
     *
     * <p>A dropped leaf can leave a section with no children at all, which is exactly what a two
     * level outline is: headings that state a topic without pretending to enumerate sub-points.</p>
     */
    private static List<ThesisOutlineNode> toTwoLevel(List<ThesisOutlineNode> nodes) {
        return nodes.stream()
                .map(node -> new ThesisOutlineNode(node.id(), node.title(), node.level(),
                        node.level() >= 2 ? List.of() : toTwoLevel(node.children())))
                .toList();
    }

    /** Mirrors the frontend's topic shortening so headings do not become a paragraph. */
    private static String shorten(String topic) {
        if (topic == null || topic.isBlank()) {
            return "待定题目";
        }
        String cleaned = topic.replace("《", "").replace("》", "").trim();
        return cleaned.length() <= 28 ? cleaned : cleaned.substring(0, 28);
    }

    /**
     * A tiny indented outline format, so the skeleton stays readable in source:
     * <pre>
     * # 第一章 绪论
     * ## 1.1 研究背景与意义
     * ### 1.1.1 现实背景
     * </pre>
     * A line starting with {@code #} opens a node; the following deeper lines become its children.
     */
    private List<ThesisOutlineNode> parse(String spec) {
        List<ThesisOutlineNode> roots = new ArrayList<>();
        // stack.get(k) holds the most recent node at level k+1.
        List<ThesisOutlineNode> stack = new ArrayList<>();
        for (String raw : spec.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#!")) {
                continue;
            }
            int level = 0;
            while (level < line.length() && line.charAt(level) == '#') {
                level++;
            }
            if (level == 0 || level > 3) {
                continue;
            }
            String title = line.substring(level).strip();
            if (title.isEmpty()) {
                continue;
            }
            ThesisOutlineNode node = new ThesisOutlineNode(nextId(), title, level, new ArrayList<>());
            while (stack.size() >= level) {
                stack.remove(stack.size() - 1);
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.get(stack.size() - 1).children().add(node);
            }
            stack.add(node);
        }
        return roots;
    }

    private String nextId() {
        return "n_" + System.currentTimeMillis() + "_" + sequence.incrementAndGet();
    }

    /**
     * Chapter skeleton. The branch specific chapter sits in third position and is the only one that
     * differs by tier; everything else is the shared "background → theory → results → conclusion"
     * shape Chinese universities expect.
     */
    private String skeleton(String tier) {
        return HEAD + CHAPTER_TWO + chapterThree(tier) + CHAPTER_FOUR + CHAPTER_FIVE + TAIL;
    }

    private String chapterThree(String tier) {
        return switch (tier == null ? "" : tier) {
            case "DOCTOR" -> """
                    # 第三章 理论建构与研究设计
                    ## 3.1 分析框架与核心命题
                    ### 3.1.1 概念模型的逻辑推导
                    ### 3.1.2 核心命题的提出与可证伪性
                    ## 3.2 研究设计与数据构建
                    ### 3.2.1 多源数据的采集与匹配
                    ### 3.2.2 关键变量的测量效度检验
                    ## 3.3 识别策略与因果推断
                    ### 3.3.1 主识别策略与假定条件
                    ### 3.3.2 多重稳健性与证伪检验
                    ## 3.4 机制检验与边界条件
                    ### 3.4.1 中介与调节机制的检验设计
                    ### 3.4.2 研究结论的适用边界
                    """;
            case "MASTER" -> """
                    # 第三章 研究假设与实证研究设计
                    ## 3.1 理论模型与研究假设
                    ### 3.1.1 变量界定与维度划分
                    ### 3.1.2 研究假设的提出
                    ## 3.2 数据来源与样本选择
                    ### 3.2.1 样本筛选标准与数据清洗
                    ### 3.2.2 变量测量与描述性统计
                    ## 3.3 实证策略与稳健性检验设计
                    ### 3.3.1 基准回归模型设定
                    ### 3.3.2 内生性处理与稳健性策略
                    """;
            case "JOURNAL" -> """
                    # 第三章 数据、变量与识别策略
                    ## 3.1 数据来源与样本构建
                    ### 3.1.1 数据来源与合并规则
                    ### 3.1.2 缺失值与异常值处理
                    ## 3.2 变量定义与测量
                    ### 3.2.1 被解释变量与解释变量
                    ### 3.2.2 控制变量与固定效应
                    ## 3.3 识别策略的可靠性论证
                    ### 3.3.1 平行趋势与安慰剂检验
                    ### 3.3.2 异质性分析框架
                    """;
            case "PROPOSAL" -> """
                    # 第三章 研究内容与拟解决的关键问题
                    ## 3.1 研究内容的分层安排
                    ### 3.1.1 核心研究模块拆解
                    ### 3.1.2 各模块之间的逻辑衔接
                    ## 3.2 拟解决的关键问题与创新点
                    ### 3.2.1 关键理论与技术难点
                    ### 3.2.2 预期创新点提炼
                    ## 3.3 可行性分析与风险预案
                    ### 3.3.1 前期基础与条件保障
                    ### 3.3.2 研究风险与应对方案
                    """;
            default -> """
                    # 第三章 系统设计与实现验证
                    ## 3.1 总体架构与技术选型
                    ### 3.1.1 系统逻辑架构与分层设计
                    ### 3.1.2 关键技术方案对比与选型依据
                    ## 3.2 核心功能模块设计
                    ### 3.2.1 数据采集与预处理模块
                    ### 3.2.2 业务处理与结果输出模块
                    ## 3.3 实验环境与结果验证
                    ### 3.3.1 实验数据集与评价指标
                    ### 3.3.2 对比实验与结果讨论
                    """;
        };
    }

    /** Chapter skeletons, kept as text so the shape stays reviewable next to the code. */
    private static final String HEAD = """
            # 第一章 绪论
            ## 1.1 研究背景与意义
            ### 1.1.1 {discipline}领域"{topic}"的现实背景
            ### 1.1.2 理论意义与实践价值
            ## 1.2 国内外研究现状
            ### 1.2.1 国外研究进展述评
            ### 1.2.2 国内研究进展述评
            ### 1.2.3 研究述评与本文切入点
            ## 1.3 研究内容与研究方法
            ### 1.3.1 主要研究内容与章节安排
            ### 1.3.2 研究方法与技术路线
            ## 1.4 研究创新点与不足
            """;

    private static final String CHAPTER_TWO = """
            # 第二章 相关概念界定与理论基础
            ## 2.1 核心概念界定
            ### 2.1.1 {major}相关概念的内涵演化
            ### 2.1.2 概念边界与本文操作化定义
            ## 2.2 理论基础
            ### 2.2.1 经典理论梳理与评述
            ### 2.2.2 理论对本文的支撑作用
            ## 2.3 国内外典型实践案例分析
            """;

    private static final String CHAPTER_FOUR = """
            # 第四章 数据结果与讨论
            ## 4.1 描述性统计与初步观察
            ## 4.2 核心结论的检验与归因
            ### 4.2.1 主结论验证
            ### 4.2.2 机制分析与异质性讨论
            ## 4.3 与既有文献的对话
            """;

    private static final String CHAPTER_FIVE = """
            # 第五章 结论与展望
            ## 5.1 主要研究结论
            ## 5.2 对策建议与管理启示
            ### 5.2.1 面向实践主体的建议
            ### 5.2.2 面向政策制定者的建议
            ## 5.3 研究局限与未来展望
            """;

    private static final String TAIL = """
            # 参考文献
            # 致谢
            """;

    /**
     * The appendix promised by the 「数据模型与代码」 switch.
     *
     * <p>Two sections, because the two halves are graded differently: 数据模型 is reviewed as a
     * design artifact (entities, fields, constraints) and 关键代码 as an implementation listing.
     * Keeping them apart also lets the writer use a different voice for each — DDL and code carry no
     * prose at all.</p>
     */
    private static final String DATA_APPENDIX = """
            # 附录 A 数据模型与关键代码
            ## A.1 数据模型设计
            ### A.1.1 实体关系与核心数据结构
            ### A.1.2 数据表结构与字段定义
            ## A.2 关键代码实现
            ### A.2.1 核心业务逻辑代码
            ### A.2.2 接口定义与调用示例
            """;

}
