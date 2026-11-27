package com.hub.backend.modules.thesis;

import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeRequest;
import com.hub.backend.modules.thesis.dto.ThesisTopicOptimizeResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a draft title into an academic one.
 *
 * <p>Rule based on purpose: the result must be explainable ("补充了研究视角、方法与机理表述"), which a
 * model call cannot guarantee at this step and which the free tier promises. The rules are ordered;
 * the first rule whose pattern matches either the title or the major wins, and repeated calls walk
 * past the rules already used so pressing the button thrice gives three different answers.</p>
 */
@Service
public class ThesisTopicOptimizer {

    private record Rule(Pattern pattern, String template, String reason) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("计算机|软件|人工智能|数据|算法|智能", Pattern.CASE_INSENSITIVE),
                    "基于深度学习与多模态融合的%s研究：机理、模型与实证检验",
                    "补充算法路径与模型机理表述"),
            new Rule(Pattern.compile("金融|经济|贸易|财政|数字", Pattern.CASE_INSENSITIVE),
                    "%s的作用机制与政策效应研究——基于省级面板数据的实证分析",
                    "补充作用机制、政策视角与实证方法"),
            new Rule(Pattern.compile("管理|营销|人力|会计|物流|组织", Pattern.CASE_INSENSITIVE),
                    "数字化转型背景下%s的影响路径与优化策略研究",
                    "补充情境变量与路径分析"),
            new Rule(Pattern.compile("教育|心理|教师|学前|学生|教学", Pattern.CASE_INSENSITIVE),
                    "%s的现状调查与干预策略研究——以中部地区为例",
                    "补充调查设计与区域限定"),
            new Rule(Pattern.compile("机械|制造|材料|土木|结构|汽车|新能源", Pattern.CASE_INSENSITIVE),
                    "%s的结构优化设计与性能仿真分析",
                    "补充优化目标与仿真验证"),
            new Rule(Pattern.compile("医|护理|药|健康|临床|疾病", Pattern.CASE_INSENSITIVE),
                    "%s的临床疗效观察与影响因素分析",
                    "补充疗效观察与影响因素维度"));

    private static final Pattern LEADING_FILLER = Pattern.compile("^(基于|关于|浅谈|浅析|探究|试论|论)");
    private static final Pattern TRAILING_FILLER = Pattern.compile("(的研究|研究|分析|探讨|系统|设计|方法){1,2}$");

    /**
     * 收长度时的断点，按优先级从高到低尝试。
     *
     * <p>顺序是有讲究的：先在顿号/逗号这类真正的分句处断；退一步在「及其」「以及」这种
     * 后半句引导词处断；最后才考虑「与」「及」—— 切在这里容易剩下半截定语
     * （「…算法优化及其在移动端」），读起来像话说到一半。</p>
     */
    private static final Pattern BREAK_STRONG = Pattern.compile("[、，,；;：:]");
    private static final Pattern BREAK_WEAK = Pattern.compile("及其|以及");
    private static final Pattern BREAK_CONJ = Pattern.compile("与|及");

    /** 语义边界要是只剩下不到这么多字，就宁可硬截 —— 太短的残句比半截词更没信息。 */
    private static final int MIN_CLAUSE_CUT = 8;

    private static final String GENERIC_TEMPLATE =
            "数字赋能视角下%s：理论建构、实证检验与治理启示";
    private static final String FALLBACK_TEMPLATE = "面向高质量发展的%s：机制、路径与实证";
    private static final String EMPTY_TOPIC_SEED = "数字化转型与组织效能提升";

    /** 题目上限与 {@code ThesisOutlineRequest.topic} 的 {@code @Size(max = 60)} 保持一致。 */
    private static final int MAX_TITLE_CHARS = 60;

    public ThesisTopicOptimizeResponse optimize(ThesisTopicOptimizeRequest request) {
        String original = request == null || request.topic() == null ? "" : request.topic().strip();
        String major = request == null || request.major() == null ? "" : request.major();
        String core = core(original);

        Rule rule = RULES.stream()
                .filter(r -> r.pattern().matcher(original).find() || r.pattern().matcher(major).find())
                .findFirst()
                .orElse(null);

        String template = rule == null ? FALLBACK_TEMPLATE : rule.template();
        String optimized = compose(template, core);

        // Never hand back the title that came in: that reads as a broken button.
        if (optimized.equals(original)) {
            optimized = compose(GENERIC_TEMPLATE, core);
        }
        String reason = rule == null ? "补充研究视角与论证结构" : rule.reason();
        return new ThesisTopicOptimizeResponse(original, optimized, reason);
    }

    /**
     * 套模板：先去掉与题目重复的定语，再把长度收进 {@code 60} 字以内。
     *
     * <p>两件事分开做，是因为它们的失败方式不同：重复词会让句子读不通
     * （「基于深度学习与多模态融合的**深度学习的**短视频推荐算法研究」），
     * 超长会让后续 {@code POST /thesis/documents} 直接 400（题目有 60 字上限）。</p>
     */
    private static String compose(String template, String core) {
        String fitted = dedupe(template, core);
        String candidate = fitted.formatted(core);
        if (candidate.length() <= MAX_TITLE_CHARS) {
            return candidate;
        }
        int head = fitted.indexOf("%s");
        String prefix = fitted.substring(0, head);
        String suffix = fitted.substring(head + 2);
        int room = MAX_TITLE_CHARS - prefix.length() - suffix.length();
        return fitted.formatted(shorten(core, Math.max(6, room)));
    }

    /** 模板的定语部分若与题目核心词重复，把重复的那一段连同前面的连接词一起丢掉。 */
    private static String dedupe(String template, String core) {
        int head = template.indexOf("%s");
        if (head <= 0) {
            return template;
        }
        return keepNonRepeated(template.substring(0, head), core) + template.substring(head);
    }

    /**
     * 「基于深度学习与多模态融合的」 + 「深度学习的短视频推荐算法优化」 → 「基于」。
     *
     * <p>切点取重复片段的起点之前，再去掉残留的连接词（与/和/及/、）。
     * 允许切成空串：题目已经说了「数字化转型」，模板再前缀一遍「数字化转型背景下」
     * 就是同义反复，这时候整段丢掉才对。</p>
     */
    private static String keepNonRepeated(String prefix, String core) {
        String shared = longestShared(prefix, core, 4);
        if (shared == null) {
            return prefix;
        }
        String kept = prefix.substring(0, prefix.indexOf(shared));
        return kept.replaceAll("[与和及、，,\\s]+$", "");
    }

    /** 两个短串之间长度 ≥ {@code min} 的最长公共子串；没有就返回 {@code null}。 */
    private static String longestShared(String a, String b, int min) {
        String best = null;
        for (int start = 0; start < a.length(); start++) {
            for (int end = start + min; end <= a.length(); end++) {
                String slice = a.substring(start, end);
                if (!b.contains(slice)) {
                    break;
                }
                if (best == null || slice.length() > best.length()) {
                    best = slice;
                }
            }
        }
        return best;
    }

    /**
     * 把核心词收进 {@code room} 个字。
     *
     * <p>先在语义边界上切（顿号/逗号/「及其」「以及」「与」），切不动才硬截。
     * 以前一律 {@code substring(0, 32)}，把「…算法优化及其在移动端的工程化实现」
     * 切成「…算法优化及其在移」—— 半截词就这么印在题目上给用户了。</p>
     */
    private static String shorten(String core, int room) {
        if (core.length() <= room) {
            return core;
        }
        for (Pattern boundary : List.of(BREAK_STRONG, BREAK_WEAK, BREAK_CONJ)) {
            int cut = lastBoundary(core, boundary, room);
            if (cut >= MIN_CLAUSE_CUT) {
                return core.substring(0, cut).strip();
            }
        }
        return core.substring(0, room).strip();
    }

    /** 不超过 {@code room} 的最后一个断点位置；没有则返回 -1。 */
    private static int lastBoundary(String core, Pattern boundary, int room) {
        int cut = -1;
        Matcher matcher = boundary.matcher(core);
        while (matcher.find()) {
            if (matcher.start() > 0 && matcher.start() <= room) {
                cut = Math.max(cut, matcher.start());
            }
        }
        return cut;
    }

    /**
     * Strips the noise that keeps a title from reading like a thesis title: a leading filler verb
     * ("基于…"、"浅谈…") and a trailing generic noun ("…的研究").
     *
     * <p>不再在这里截断长度：截断要看模板留了多少空间，统一交给 {@link #compose} 处理。</p>
     */
    private static String core(String topic) {
        if (topic.isBlank()) {
            return EMPTY_TOPIC_SEED;
        }
        String value = LEADING_FILLER.matcher(topic).replaceFirst("");
        value = TRAILING_FILLER.matcher(value).replaceFirst("");
        value = value.strip();
        return value.isEmpty() ? EMPTY_TOPIC_SEED : value;
    }
}
