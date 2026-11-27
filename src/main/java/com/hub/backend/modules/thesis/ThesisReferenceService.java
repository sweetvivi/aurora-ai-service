package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Real bibliographic lookup behind the reference list.
 *
 * <p><strong>Why not CNKI.</strong> CNKI's search endpoint answers an automated client with
 * {@code HTTP 418} and a bot wall — scraping it would mean defeating an access control, which this
 * project will not do. What it does instead is use OpenAlex, an open scholarly index that is
 * queryable by API, and keep CNKI-adjacent material by <em>filtering</em>: only Chinese-language
 * works with a DOI are returned, because a DOI is the one field a reader can click to verify the
 * citation actually exists. A made-up reference is worse than no reference, so anything that cannot
 * be resolved to a DOI is dropped rather than dressed up.</p>
 *
 * <p>The returned records are handed to the prompt as fixed facts: the model may cite them and
 * nothing else. That split is the whole point — the model writes prose, the index decides what
 * exists.</p>
 */
@Component
public class ThesisReferenceService {

    private static final Logger log = LoggerFactory.getLogger(ThesisReferenceService.class);

    /**
     * Per word, before the DOI filter.
     *
     * <p>Only about a quarter of Chinese records in this index carry a DOI (measured: 情感分析 4/20,
     * 金融舆情 9/20), and those without one are dropped because a citation a reader cannot verify is
     * worse than no citation. The per-page size is larger than the target count for that reason —
     * asking for 10 tends to leave 2 after filtering.</p>
     */
    private static final int QUERY_PER_WORD = 50;

    /** GB/T 7714 lists at most three authors, then 等. */
    private static final int MAX_AUTHORS = 3;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final int timeoutMs;

    public ThesisReferenceService(ObjectMapper objectMapper,
                                  @Value("${app.thesis.reference.enabled:true}") boolean enabled,
                                  @Value("${app.thesis.reference.base-url:https://api.openalex.org}") String baseUrl,
                                  @Value("${app.thesis.reference.api-key:}") String apiKey,
                                  @Value("${app.thesis.reference.timeout-ms:8000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 4000)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * One citation, already shaped for the prompt.
     *
     * @param authors author names in <em>surname-first</em> order joined by "、", already normalised
     *                by {@link #normaliseAuthorName}; may be empty when the index has none
     * @param doi     resolvable DOI, never null — this is the field the reader verifies
     */
    public record Reference(String title, String authors, String source, Integer year, String doi) {

        /**
         * GB/T 7714 style, which is what a Chinese thesis reference list is expected to use.
         *
         * <p>Chinese entries use full-width punctuation (「．」「，」) and Western entries half-width,
         * because the document mixes both: a Chinese thesis quoting an English paper should not get
         * 「. ，」 spliced into an English citation. The separator follows the <em>title</em> — a
         * Chinese title with an English journal name is still a Chinese entry.</p>
         *
         * <p>Two shapes that were wrong before: the year fallback produced {@code n.d..} (double
         * stop), and a missing year was printed as if it were a valid date. Both are now either
         * punctuated correctly or omitted.</p>
         */
        public String citation() {
            String who = authors == null || authors.isBlank() ? "佚名" : authors;
            String where = source == null || source.isBlank() ? "未知来源" : source;
            boolean cjk = isCjk(title) || isCjk(who);
            String comma = cjk ? "，" : ", ";
            String stop = cjk ? "．" : ". ";

            StringBuilder out = new StringBuilder();
            out.append(who).append(stop).append(title).append("[J]").append(stop).append(where);
            if (year != null) {
                out.append(comma).append(year);
            }
            out.append(cjk ? "．" : ".");
            if (doi != null && !doi.isBlank()) {
                out.append(" DOI: ").append(doi);
            }
            return out.toString();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Searches for works matching the topic.
     *
     * <p>Failures are logged and turned into an empty list on purpose: the reference list is an
     * enrichment of the full text, and losing the index should downgrade the bibliography, not the
     * whole generation. The caller tells the user when the list is empty.</p>
     *
     * <p>The query is built from <em>topic keywords</em> rather than the full title: OpenAlex's
     * {@code default.search} is an OR-ish full-text match, so a whole sentence pulls in anything that
     * shares one common character — a 30-character title returns works about education reform and
     * 党建 when the thesis is about financial sentiment analysis. Short keyword sets keep precision.</p>
     *
     * @param query keyword phrase, e.g. {@code "金融舆情 大语言模型"}
     * @param count how many references to aim for
     */
    public List<Reference> search(String query, int count) {
        if (!enabled || query == null || query.isBlank() || count <= 0) {
            return List.of();
        }
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        // One request per topic word, then merged. Asking OpenAlex for all the words at once makes
        // it rank by the combined phrase, and a paper that perfectly matches 金融舆情 but says
        // nothing about 计算机 never surfaces — 25 candidates yielded 3 references that way. Per-word
        // queries surface each dimension, and the relevance filter keeps only the useful ones.
        List<Reference> collected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : tokens) {
            for (Reference reference : searchOne(token, QUERY_PER_WORD)) {
                if (seen.add(reference.title().toLowerCase(Locale.ROOT))) {
                    collected.add(reference);
                }
            }
        }

        List<Reference> ranked = rankByRelevance(collected, query, count);
        log.info("References for '{}': {} candidates from {} queries, {} kept",
                query, collected.size(), tokens.size(), ranked.size());
        return ranked;
    }

    /** Raw lookup for a single word, with no relevance filtering. */
    private List<Reference> searchOne(String query, int perPage) {
        try {
            String filter = "default.search:" + encode(query) + ",language:zh";
            String url = baseUrl + "/works?filter=" + filter
                    + "&per-page=" + perPage
                    + "&sort=relevance_score:desc"
                    + "&select=id,doi,title,authorships,publication_year,primary_location"
                    + (apiKey == null || apiKey.isBlank() ? "" : "&api_key=" + encode(apiKey));

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .header("User-Agent", "thesis-assistant/1.0 (local development)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("Reference lookup for '{}' returned {}: {}", query, response.statusCode(),
                        abbreviate(response.body()));
                return List.of();
            }
            return parse(response.body(), perPage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception exception) {
            log.warn("Reference lookup for '{}' failed: {}", query, exception.getMessage());
            return List.of();
        }
    }

    /** Extracted so it can be tested without a network round trip. */
    List<Reference> parse(String body, int count) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
        List<Reference> out = new ArrayList<>();
        Set<String> seenTitles = new LinkedHashSet<>();

        for (JsonNode work : results) {
            String doi = text(work.path("doi"));
            String title = text(work.path("title"));
            // No DOI or no title means the reader cannot verify it: drop rather than guess.
            if (doi == null || title == null || !seenTitles.add(title.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String source = text(work.path("primary_location").path("source").path("display_name"));
            Integer year = work.path("publication_year").isNumber()
                    ? work.path("publication_year").asInt()
                    : null;

            out.add(new Reference(title, authors(work.path("authorships")), source, year, doi));
            if (out.size() >= count) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /**
     * Keeps only references that are plausibly about the topic.
     *
     * <p>Two-step rule, both parts learned from a real failure. Searching the full title
     * 「基于大语言模型的金融舆情分析系统设计与实现」 returned a paper about 室内风水设计 for a thesis on
     * financial sentiment: OpenAlex matched on 「的」 and 「研究」, which every Chinese academic title
     * contains. So the check is not "shares any substring" but:</p>
     *
     * <ol>
     *   <li>the title must contain <b>one of the caller's substantive phrases</b> (a whole keyword
     *       such as 金融舆情, 大语言模型), which is what makes a citation relevant;</li>
     *   <li>failing that, it may still pass on <b>two independent fragments</b> from different words
     *       — the shape of a paper that genuinely sits between two fields (舆情 + 大语言模型).</li>
     * </ol>
     *
     * <p>One fragment alone is never enough, because one fragment is how the interior-design paper
     * got in.</p>
     */
    List<Reference> rankByRelevance(List<Reference> candidates, String query, int count) {
        List<List<String>> groups = fragmentGroups(query);
        if (groups.isEmpty()) {
            return candidates.size() <= count ? candidates : candidates.subList(0, count);
        }

        // Ranking is by how many distinct query words the title covers, weighted so that the
        // subject-matter words (舆情 / 情感分析 / 大语言模型) count for more than the field words
        // (金融 / 计算机). The first rule tried summed weights, which scored a paper containing only
        // 大语言模型 at 3.0 and one containing 情感分析 + 金融 at 4.0 — close enough that page order
        // decided the rest, and the English-teaching papers won on volume.
        //
        // The fix is a bonus for breadth rather than a bigger weight: covering two words is worth
        // much more than covering the strongest single one, because a title that mentions both the
        // subject and the field is precisely the citation a supervisor expects to see.
        record Scored(Reference reference, double score) {
        }
        List<String> queryWords = tokens(query);
        String whole = query.replace(" ", "").toLowerCase(Locale.ROOT);

        Set<String> broad = Set.of("金融", "经济", "管理", "计算机", "教育", "大学", "中学");

        List<Scored> scored = new ArrayList<>();
        for (Reference reference : candidates) {
            // Title only. Including the journal name let 中国科学与技术学报 contribute 「科学」「技术」
            // to an interior-design paper's score, which is how it slipped through before.
            String haystack = reference.title().toLowerCase(Locale.ROOT);

            int specificCovered = 0;
            int broadCovered = 0;

            for (String word : queryWords) {
                boolean covered = haystack.contains(word.toLowerCase(Locale.ROOT));
                if (!covered) {
                    // Fall back to two fragments of the same word: 「金融舆情」 may appear split by
                    // other characters, as in 金融事件级情感分析.
                    List<String> grams = fragmentGroups(word).stream().findFirst().orElse(List.of());
                    covered = grams.stream().filter(haystack::contains).count() >= 2;
                }
                if (covered) {
                    if (broad.contains(word)) {
                        broadCovered++;
                    } else {
                        specificCovered++;
                    }
                }
            }

            // Breadth first: subject-matter coverage dominates, then field coverage, and a paper
            // hitting both is rewarded above one that only stacks up subject-matter words.
            double score = specificCovered * 10.0 + broadCovered * 4.0;
            if (specificCovered > 0 && broadCovered > 0) {
                score += 15.0;
            }

            boolean exactWhole = !whole.isEmpty() && haystack.contains(whole);
            if (exactWhole) {
                score += 100;
            }

            // Keep anything that covers the subject matter at all, or the whole topic phrase.
            if (specificCovered > 0 || broadCovered >= 2 || exactWhole) {
                scored.add(new Scored(reference, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (log.isDebugEnabled()) {
            log.debug("Ranked {} candidates for '{}'; top: {}", scored.size(), query,
                    scored.stream().limit(5)
                            .map(item -> String.format("%.0f|%s", item.score(), item.reference().title()))
                            .toList());
        }
        List<Reference> kept = new ArrayList<>();
        for (Scored item : scored) {
            if (kept.size() >= count) {
                break;
            }
            kept.add(item.reference());
        }
        return List.copyOf(kept);
    }

    /**
     * The caller's keywords as whole phrases — the strong signal.
     *
     * <p>Also emitted: adjacent two-word combinations, because a caller writing
     * {@code 金融舆情 大语言模型} should match a title containing 大语言模型的金融舆情.</p>
     */
    static List<String> phrases(String query) {
        List<String> tokens = tokens(query);
        Set<String> out = new LinkedHashSet<>(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            out.add(tokens.get(i) + tokens.get(i + 1));
        }
        return List.copyOf(out);
    }

    /**
     * Words that appear in a third of all Chinese academic titles.
     *
     * <p>They carry no topic: a thesis on financial sentiment and a paper on interior design both
     * say 「设计」 and 「研究」. Accepting them as evidence is exactly how the wrong paper got cited,
     * so they are stripped before fragments are cut.</p>
     */
    private static final Set<String> GENERIC_WORDS = Set.of(
            "技术", "研究", "分析", "设计", "系统", "方法", "应用", "实现", "探讨", "问题",
            "理论", "实践", "模式", "策略", "构建", "发展", "中国", "我国", "及其", "基于");

    /**
     * Overlapping bigrams of each CJK keyword, kept grouped by the word they came from.
     *
     * <p>Grouping matters: a match needs two fragments <em>from the same word</em>, which is what
     * 「金融舆情」 appearing in a title looks like. Bigrams from different words are not evidence of
     * anything, and pooling them let an unrelated 财务风险 paper pass on 「金融」+「模型」.</p>
     */
    static List<List<String>> fragmentGroups(String query) {
        List<List<String>> groups = new ArrayList<>();
        for (String token : tokens(query)) {
            List<String> group = new ArrayList<>();
            if (token.matches("[A-Za-z0-9.-]+")) {
                group.add(token);
            } else {
                for (int i = 0; i + 2 <= token.length(); i++) {
                    String gram = token.substring(i, i + 2);
                    if (!GENERIC_WORDS.contains(gram)) {
                        group.add(gram);
                    }
                }
            }
            if (!group.isEmpty()) {
                groups.add(List.copyOf(group));
            }
        }
        return List.copyOf(groups);
    }

    /** Flat view of {@link #fragmentGroups}, for diagnostics and tests. */
    static List<String> fragments(String query) {
        Set<String> out = new LinkedHashSet<>();
        fragmentGroups(query).forEach(out::addAll);
        return List.copyOf(out);
    }

    private static List<String> tokens(String query) {
        List<String> out = new ArrayList<>();
        for (String part : query.split("[\\s,，、;；/]+")) {
            String token = part.strip();
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Author names for the citation, in the order a Chinese reference list expects.
     *
     * <p><strong>Why this is not just {@code display_name}.</strong> OpenAlex stores Chinese names
     * the way the Western convention does — <em>given-family</em>, e.g. {@code 钰琪 杨} — so printing
     * {@code display_name} verbatim produced 「钰琪 杨、青 严、晔 韩」: the name is "correct" data, the
     * order is wrong for GB/T 7714, and a supervisor reads it as a broken citation. The full text is
     * exported to Word as a deliverable, so this is not cosmetic.</p>
     *
     * <p>Recovery order, strongest signal first:</p>
     * <ol>
     *   <li>a comma in {@code raw_author_name} ({@code Liu, Ming}) states the split explicitly;</li>
     *   <li>a space in a CJK name ({@code 钰琪 杨}) is the given/family split, so swap it;</li>
     *   <li>a CJK name without a space is already complete ({@code 张淼}) — leave it alone.</li>
     * </ol>
     *
     * <p>Latin names are kept as-is: 「Ming Liu」 is the normal form in an English reference and
     * reordering it would be the same bug in the other direction.</p>
     */
    static String authors(JsonNode authorships) {
        if (authorships == null || !authorships.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        int total = 0;
        for (JsonNode authorship : authorships) {
            for (String name : normaliseAuthorship(
                    text(authorship.path("raw_author_name")),
                    text(authorship.path("author").path("display_name")))) {
                total++;
                if (names.size() < MAX_AUTHORS && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        // GB/T 7714: 三位以内全列，超过三位列前三并加「等」—— 少这个「等」会让读者以为
        // 这篇文章只有三位作者，比截断本身错得更明显。
        return String.join("、", names) + (total > MAX_AUTHORS ? "等" : "");
    }

    /**
     * One authorship → the names it actually contains.
     *
     * <p>Usually one. It returns several because the index occasionally packs a whole author list
     * into a single {@code raw_author_name}, as in
     * {@code 「范子杨 闫屹伟，李昕明，朱志刚」}. Treating that as one name produced
     * 「闫屹伟，李昕明，朱志刚范子杨」 in a real citation — the full-width comma survived, the names
     * stayed fused, and it reads as a corrupted entry rather than a list. Splitting here is the only
     * place the information still exists.</p>
     */
    static List<String> normaliseAuthorship(String raw, String display) {
        String source = raw != null && !raw.isBlank() ? raw : display;
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String text = source.strip();

        List<String> names = new ArrayList<>();

        /*
         * 半角逗号与全角逗号的含义完全不同，必须先分开处理：
         *  - 「徐, 远航」「Liu, Ming」是**一个人**，逗号是作者自己标的「姓, 名」；
         *  - 「范子杨 闫屹伟，李昕明，朱志刚」是**四个人**，全角逗号是列表分隔。
         * 早期版本把两者都当分隔符，于是「徐, 远航」被拆成「徐,」和「远航」两个作者，
         * 导出后就是一条明显坏掉的文献。
         */
        boolean packedList = text.indexOf('，') >= 0 || text.indexOf('；') >= 0 || text.indexOf(';') >= 0;
        if (!packedList && text.indexOf(',') > 0) {
            String formatted = formatCommaSplitName(text);
            return formatted == null ? List.of() : List.of(formatted);
        }

        for (String segment : text.split("[，；;]")) {
            for (String piece : splitPackedNames(segment)) {
                String formatted = formatSingleName(piece);
                if (formatted != null && !formatted.isBlank()) {
                    names.add(formatted);
                }
            }
        }
        return List.copyOf(names);
    }

    /** 「姓, 名」→ 姓在前。中西文都走这条，因为逗号本身就是作者的切分。 */
    private static String formatCommaSplitName(String text) {
        int comma = text.indexOf(',');
        if (comma <= 0) {
            return null;
        }
        String family = text.substring(0, comma).strip();
        String given = text.substring(comma + 1).strip();
        if (family.isEmpty() || given.isEmpty()) {
            return null;
        }
        return family + (isCjk(family) ? given : " " + given);
    }

    /**
     * 处理「范子杨 闫屹伟」「钰琪 杨」这两种都带空格、但含义完全相反的写法。
     *
     * <p>判据是<b>最后一个词是不是姓</b>：</p>
     * <ul>
     *   <li>{@code 钰琪 杨} —— 末词「杨」是单字姓，前一词「钰琪」是名，所以是「名 姓」，
     *       需要交换成 杨钰琪；</li>
     *   <li>{@code 范子杨 闫屹伟} —— 末词是三个字，不可能是姓，所以这是<b>两个人</b>，
     *       各自本来就是「姓 名」顺序，不能再交换（交换会得到「闫屹伟范子杨」这种六字怪名）。</li>
     * </ul>
     *
     * <p>一开始只按「词数 = 2 就交换」处理，真实数据立刻把这个假设打破了 —— 一篇四位作者的
     * 论文被印成「闫屹伟，李昕明，朱志刚范子杨」。</p>
     *
     * <p>返回的每一段都是<b>未格式化</b>的原始片段，交给 {@link #formatSingleName} 处理。
     * 这两个函数不能互相调用：先是 splitPackedNames 回调格式化，格式化又回调拆分，
     * 实测直接把 JVM 栈打爆（StackOverflowError 表现为 PatternSyntaxException）。</p>
     */
    private static List<String> splitPackedNames(String segment) {
        String text = segment.strip();
        if (text.isEmpty()) {
            return List.of();
        }

        String[] tokens = text.split("\\s+");
        if (tokens.length <= 1) {
            return List.of(text);
        }

        for (String token : tokens) {
            // 含西文的（"Ming Liu"、"Yuchen (蔡雨晨) Cai"）不按空格拆，交给单名规则
            if (!isCjk(token)) {
                return List.of(text);
            }
        }

        String last = tokens[tokens.length - 1];
        boolean lastLooksLikeSurname = last.length() == 1 || COMPOUND_SURNAMES.contains(last);
        boolean firstFitsAsGivenName = tokens[0].length() <= 3;

        if (lastLooksLikeSurname && firstFitsAsGivenName) {
            // 「名 姓」：末词是姓，交换
            return List.of(last + String.join("", java.util.Arrays.copyOf(tokens, tokens.length - 1)));
        }

        // 否则每个词都是一个完整的「姓 名」
        return List.of(tokens);
    }

    /**
     * 常见复姓。
     *
     * <p>只用来判断「末词能不能当姓」。漏掉一个复姓的后果是那个作者的名字不被交换，
     * 顺序维持原状 —— 比误判成两个人要轻得多，所以这份表不必求全。</p>
     */
    private static final Set<String> COMPOUND_SURNAMES = Set.of(
            "欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公孙", "慕容", "长孙",
            "宇文", "司徒", "司空", "令狐", "夏侯", "端木", "独孤", "南宫", "轩辕", "闻人",
            "澹台", "公冶", "宗政", "濮阳", "淳于", "单于", "太叔", "申屠", "仲孙", "钟离",
            "拓跋", "夹谷", "万俟", "呼延", "羊舌", "第五");

    /**
     * 把一个已经切成单人的片段格式化。
     *
     * @param piece 单人片段，可能是「钰琪 杨」「范子杨」「Ming Liu」「张淼」
     */
    private static String formatSingleName(String piece) {
        String name = piece.strip();
        if (name.isEmpty()) {
            return null;
        }

        // "Yuchen (蔡雨晨) Cai"：括号里是中文姓名，且本身就是「姓 名」顺序
        if (name.indexOf('(') >= 0) {
            int open = name.indexOf('(');
            int close = name.indexOf(')', open + 1);
            if (close > open) {
                String inner = name.substring(open + 1, close).strip();
                if (isCjk(inner) && inner.length() >= 2) {
                    return inner;
                }
            }
        }

        String[] parts = name.split("\\s+");
        if (parts.length != 2) {
            // 单字（张淼）或已是「姓 名」（范子杨）：原样返回
            return name;
        }

        boolean bothCjk = isCjk(parts[0]) && isCjk(parts[1]);
        if (!bothCjk) {
            // 西文名（"Ming Liu"）：保持原样，重排就是同一个 bug 的反向版本
            return name;
        }

        // 走到这里说明 splitPackedNames 判定它是「名 姓」，交换成「姓名」
        return parts[1] + parts[0];
    }

    /**
     * Turns one author's raw / display names into a single displayable name.
     *
     * @param raw     {@code raw_author_name}, which may be {@code "杨, 钰琪"} or just {@code "钰琪 杨"}
     * @param display {@code author.display_name}, the given-first rendering
     */
    static String normaliseAuthorName(String raw, String display) {
        List<String> names = normaliseAuthorship(raw, display);
        // 打包多人时用顿号连接，至少读起来是若干个人，而不是一团字
        return names.isEmpty() ? null : String.join("、", names);
    }

    /** True when the text is Chinese/Japanese/Korean script rather than Latin. */
    static boolean isCjk(String value) {
        return value != null && value.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /**
     * Rewrites an already-stored citation whose author names are still given-first.
     *
     * <p><strong>Why this exists.</strong> The citation text is rendered at generation time and
     * persisted as a string, so fixing the generator only helps the <em>next</em> thesis. A user who
     * already generated one would keep exporting 「钰琪 杨」 until they paid to regenerate — which is
     * not a reasonable thing to ask for a formatting bug. This repairs on read instead, and is
     * idempotent: a name already in surname-first order (single token) is left untouched, so running
     * it on new data changes nothing.</p>
     *
     * <p>Only the author segment is examined — everything from the first separator on is title,
     * journal and year, where a space never separates a given name from a family name.</p>
     */
    static String repairLegacyCitation(String citation) {
        if (citation == null || citation.isBlank()) {
            return citation;
        }
        // 老格式用半角「．/． 」分隔，新格式用全角「．」；两种都要能认出作者段的结束位置
        int cut = -1;
        int half = citation.indexOf(". ");
        if (half >= 0) {
            cut = half;
        }
        int full = citation.indexOf('．');
        if (full >= 0 && (cut < 0 || full < cut)) {
            cut = full;
        }
        if (cut <= 0) {
            return citation;
        }

        String authors = citation.substring(0, cut);
        String rest = citation.substring(cut);
        if (!isCjk(authors)) {
            // 纯西文条目，作者本来就是名在前，不动
            return citation;
        }

        List<String> fixed = new ArrayList<>();
        boolean changed = false;
        // 老数据里作者之间可能是「、」，也可能是一整段全角逗号拼起来的
        for (String part : authors.split("[、，；;]")) {
            String name = part.strip();
            if (name.isEmpty()) {
                continue;
            }
            String swapped = normaliseAuthorName(name, null);
            if (swapped != null && !swapped.equals(name)) {
                changed = true;
            }
            fixed.add(swapped == null ? name : swapped);
        }
        if (!changed) {
            return citation;
        }
        // 统一成顿号：GB/T 7714 里作者之间就是「、」，混着全角逗号既不对也不齐
        return String.join("、", fixed) + rest;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").strip();
        return value.isEmpty() ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
