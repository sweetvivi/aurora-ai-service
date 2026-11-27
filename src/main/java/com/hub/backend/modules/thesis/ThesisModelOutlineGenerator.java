package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.LlmFailures;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the outline with an actual language model.
 *
 * <p>This is the optional second half of the outline engine. {@link ThesisOutlineGenerator} stays
 * the deterministic fallback: it needs no credentials, no network and no tokens, so the "free
 * outline" step keeps working on a machine with no upstream key. When a key <em>is</em> available
 * this class asks the model instead, and the caller merges the answer with the template so a
 * partial or malformed reply can never produce a worse result than the template alone.</p>
 *
 * <p>Two deliberate restrictions:</p>
 * <ul>
 *   <li><b>Only headings come from the model.</b> Generating the body text is a separate, billed
 *       feature (the drawer's "生成全文"); letting the model write prose here would make a step
 *       advertised as free silently consume tokens.</li>
 *   <li><b>The output is parsed, not trusted.</b> Models return numbered lists, markdown headings,
 *       JSON, or a short apology. {@link #parseOutline} accepts the first three shapes and returns
 *       an empty list for anything else, which the caller treats as "fall back to the template".</li>
 * </ul>
 */
@Component
public class ThesisModelOutlineGenerator {

    private static final Logger log = LoggerFactory.getLogger(ThesisModelOutlineGenerator.class);

    /** Chapters beyond this are almost always the model repeating itself. */
    private static final int MAX_CHAPTERS = 9;

    /**
     * Output budget for the outline.
     *
     * <p>Not just "cheapest guard against a runaway reply": thinking models bill their reasoning
     * against the same budget, and a three level outline for six chapters is easily 1200+ tokens on
     * its own. At 2048 the reasoning phase ate most of the allowance and the reply was cut off after
     * the first chapter heading — which then failed the "has children" check and silently served the
     * template. 4096 matches the full text generator.</p>
     */
    private static final int MAX_TOKENS = 4096;

    /** 大纲只有一次机会（失败即回落模板），给两次尝试：抖动一次还能拿到真实模型的结果。 */
    private static final int OUTLINE_MAX_ATTEMPTS = 2;

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final NewApiProperties newApiProperties;
    private final ModelUseResolver modelResolver;
    private final UserRelayCredentialResolver relayCredentials;
    private final ObjectMapper objectMapper;

    public ThesisModelOutlineGenerator(LlmClient llmClient,
                                       LlmProperties llmProperties,
                                       NewApiProperties newApiProperties,
                                       ModelUseResolver modelResolver,
                                       UserRelayCredentialResolver relayCredentials,
                                       ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.newApiProperties = newApiProperties;
        this.modelResolver = modelResolver;
        this.relayCredentials = relayCredentials;
        this.objectMapper = objectMapper;
    }

    /**
     * Whether a model call is worth attempting at all.
     *
     * <p>Checked before spending a request: without this, a deployment with no upstream key answers
     * every outline request with a 503 instead of quietly using the template. Also lets the
     * catalogue tell the client which path it will take.</p>
     */
    public boolean available() {
        if (newApiProperties.isEnabled()) {
            // Signed-in users supply their own New API login. The service only needs a reachable
            // base URL and the at-rest key used for their encrypted relay credentials.
            return notBlank(newApiProperties.resolvedBaseUrl())
                    && notBlank(newApiProperties.getEncryptionKey());
        }
        return notBlank(llmProperties.getApiKey());
    }

    /**
     * Asks the model for an outline.
     *
     * @param modelKey  model to use; blank or {@code null} falls back to the configured default
     * @param currentUser caller, {@code null} for an anonymous visitor (then only the static key
     *                    path can be used — a guest has no relay account)
     * @return the parsed headings, or an empty list when the model was unusable. Never throws for
     *         a bad answer: the caller has a template to fall back on, and a failed model should
     *         not turn a working free feature into an error.
     */
    public List<ThesisOutlineNode> generate(String modelKey,
                                            String educationLevel,
                                            String paperType,
                                            String discipline,
                                            String major,
                                            String topic,
                                            int depth,
                                            boolean withData,
                                            Long siteId,
                                            CurrentUser currentUser) {
        ProviderCredentials credentials;
        try {
            credentials = credentials(currentUser);
        } catch (RuntimeException exception) {
            // No key, no relay account, or a disabled account: not an error, just "use the template".
            // Logged at WARN because a silent fallback looks identical to a working template request
            // from the outside — the caller only sees mode=TEMPLATE, so this line is the only way to
            // tell "asked for the model, was refused" apart from "didn't ask for the model".
            log.warn("Model outline skipped, credentials unavailable: {}", exception.toString());
            return List.of();
        }

        // 站点 key → 上游模型名。以前这里直接 modelKey.strip() 原样发出去：
        // resolveModelKey() 写了却没人调用，成了死方法 —— 页面选 deepseek-v4.1-flash、
        // 渠道只认 deepseek-v4-1-flash-260910 时就是 503 model_not_found。
        String key = resolveModelKey(modelKey, siteId);

        LlmChatCommand command = new LlmChatCommand(
                key,
                List.of(
                        LlmChatMessage.system(systemPrompt()),
                        LlmChatMessage.user(userPrompt(educationLevel, paperType, discipline,
                                major, topic, depth, withData))),
                0.4,
                MAX_TOKENS,
                credentials);

        try {
            // Streaming, not `complete()`: the upstream rejects non-stream requests on some channel
            // groups ("非流式请求已禁用（分组 svip63），请使用 stream=true", HTTP 400). That arrived
            // as an intermittent failure — the same request succeeded a minute later — and every
            // occurrence silently served the template, which reads as "the model call didn't work".
            // A stream is also what every other model path in this app already uses.
            //
            // 瞬时失败（429 / 5xx / 连接重置）重试一次：本来就会回落模板，多等一秒换一次真实模型
            // 是值得的 —— 用户看到的差别是「AI 生成的大纲」和「模板大纲」。余额不足、密钥无效
            // 这类确定性失败不重试，直接回落。
            Streamed streamed = streamWithRetry(key, command);
            String raw = streamed.content();

            List<ThesisOutlineNode> parsed = parseOutline(raw, depth);
            if (parsed.isEmpty()) {
                // 打印开头一段原始输出：解析失败时，只有看到模型实际回了什么才能判断是格式跑偏
                // 还是被 token 上限截断。只记前 400 字，避免日志被整篇大纲刷屏。
                log.warn("Model '{}' returned no usable outline headings, falling back to the template. "
                                + "raw(length={}): {}",
                        key, raw.length(),
                        raw.length() <= 400 ? raw : raw.substring(0, 400) + "…");
            }
            return parsed;
        } catch (LlmInvocationException exception) {
            // Upstream down / key rejected / model retired: the free feature must still answer.
            log.warn("Model outline failed on '{}', falling back to the template: {}",
                    key, exception.getMessage());
            return List.of();
        }
    }

    /** 一次流式调用的产物：正文与用量。 */
    private record Streamed(String content, int tokens) {
    }

    /** 流式取回整段文本；瞬时失败重试一次。 */
    private Streamed streamWithRetry(String key, LlmChatCommand command) {
        for (int attempt = 1; ; attempt++) {
            try {
                StringBuilder content = new StringBuilder();
                LlmUsage[] usage = new LlmUsage[1];
                llmClient.stream(command, chunk -> {
                    if (chunk.hasContent()) {
                        content.append(chunk.contentDelta());
                    }
                    if (chunk.usage() != null) {
                        usage[0] = chunk.usage();
                    }
                });
                return new Streamed(content.toString(),
                        usage[0] == null ? 0 : usage[0].totalTokensOrZero());
            } catch (LlmInvocationException exception) {
                if (attempt >= OUTLINE_MAX_ATTEMPTS || !LlmFailures.isRetryable(exception)) {
                    throw exception;
                }
                log.warn("Outline call failed on '{}' (attempt {}/{}): {} — retrying",
                        key, attempt, OUTLINE_MAX_ATTEMPTS, exception.getMessage());
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    /**
     * Credentials for this call.
     *
     * <p>Same rule as the chat endpoints — a signed-in caller is billed through their own relay
     * token — except that a guest cannot have one. Guests therefore only work on the
     * relay-disabled development path, which is exactly the local setup this feature is tested in.</p>
     */
    private ProviderCredentials credentials(CurrentUser currentUser) {
        if (newApiProperties.isEnabled()) {
            if (currentUser == null) {
                throw new IllegalStateException("anonymous callers have no relay credential");
            }
            return relayCredentials.resolve(currentUser);
        }
        return new ProviderCredentials(
                "OPENAI_COMPATIBLE", llmProperties.resolvedBaseUrl(), llmProperties.getApiKey(),
                null, null, null);
    }

    /**
     * The model key this generator would use when the caller does not name one.
     *
     * <p>Echoed back in the response so a client that relied on the default still learns which model
     * produced the headings.</p>
     */
    public String resolvedModelKey() {
        return llmProperties.getDefaultModel();
    }

    /** Resolves the catalogue entry so a key registered as an IMAGE model is rejected, not sent. */
    public String resolveModelKey(String requested, Long siteId) {
        // 回退也要解析：以前 catch 里直接 return llmProperties.getDefaultModel()，
        // 那是站点 key，而成功路径返回的是上游模型名 —— 同一个方法两种口径。
        return modelResolver.resolveOrDefault(siteId, requested, LlmModelEntity.TYPE_CHAT)
                .upstreamKey();
    }

    private static String systemPrompt() {
        return """
                你是中文学位论文的大纲规划专家，熟悉中国高校学位论文的章节结构规范。
                你的输出会被程序解析，所以必须严格遵守格式要求，不要写任何解释、前言或结尾语。
                """.strip();
    }

    /**
     * The instruction encodes the shape the parser expects.
     *
     * <p>Being explicit about "no explanation, no numbering" is what makes the reply parseable;
     * every earlier attempt that only described the content came back with a paragraph of
     * encouragement followed by a numbered list.</p>
     */
    private static String userPrompt(String educationLevel, String paperType, String discipline,
                                     String major, String topic, int depth, boolean withData) {
        String depthRule = depth <= 2
                ? "只输出两级：章（一级）和节（二级），不要输出第三级。"
                : "输出三级：章（一级）、节（二级）、目（三级），每个二级标题下给出 2-3 个三级标题。";

        // The type is not decoration: 「教学设计」must read like a lesson plan and 「文献综述」must be
        // organised by research themes. Without this line every type produced the same thesis
        // skeleton, so the picker appeared to do nothing.
        PaperTypeProfile profile = PaperTypeProfile.byLabel(paperType);

        // 增值项必须写进提示词，否则模型永远不知道用户勾了「数据模型与代码」：
        // 模板引擎那边有 appendDataAppendix，两边对同一个开关要给出同一个结论。
        String dataRule = withData
                ? """
                  6. 本论文需要附带数据模型与关键代码：在「参考文献」之前追加一个一级标题
                     「附录 A 数据模型与关键代码」，其下给出两个二级标题：
                     「数据模型设计」（实体关系 / 表结构，或变量定义 / 量表结构）与
                     「关键代码实现」（核心业务逻辑、接口调用示例）。
                  """
                : "6. 不要输出「附录」类标题，也不要安排数据模型或代码章节。\n";

        return """
                请为下列论文设计大纲：
                - 题目：%s
                - 文本类型：%s（%s）
                - 学历层次：%s
                - 学科：%s / %s
                - 结构层级：%s

                文本类型的结构要求（务必按这个体例组织章节，而不是套用通用学位论文骨架）：
                %s

                格式要求（必须严格遵守）：
                1. 每个标题单独一行，行首用 # 的数量表示层级：一级 1 个 #，二级 2 个 #，三级 3 个 #。
                2. 一级标题写「第X章 名称」，X 用中文数字；二级 / 三级标题不要自己编号，程序会自动编号。
                3. 除了标题行，不要输出任何其它文字，不要输出序号列表，不要使用 markdown 代码块。
                4. 一级标题控制在 5-6 个，最后一章为「结论与展望」，末尾可附「参考文献」与「致谢」两个一级标题。
                5. 每个标题不超过 20 个字，只写标题本身，不要写成句子或补充说明。

                示例（仅供参考格式）：
                # 第一章 绪论
                ## 研究背景与意义
                ### 现实背景
                ### 理论价值
                ## 国内外研究现状
                """.formatted(topic, profile.label(), profile.description(), educationLevel,
                discipline, major, depthRule, profile.outlineGuide()).strip() + "\n" + dataRule;
    }

    /* ------------------------------- 解析 ------------------------------- */

    /**
     * Parses whatever the model returned into a tree.
     *
     * <p>Three shapes are accepted, in the order they are cheapest to detect:</p>
     * <ol>
     *   <li><b>{@code #}-prefixed markdown headings</b> — the instructed format;</li>
     *   <li><b>JSON</b> — {@code [{"title","children"}]} or {@code {"chapters":[…]}}, because models
     *       asked for a structure sometimes volunteer one anyway;</li>
     *   <li><b>Indented plain text</b> — a numbered or tab-indented list, the most common deviation.</li>
     * </ol>
     *
     * @return empty when nothing usable was found; callers must treat that as a failure
     */
    List<ThesisOutlineNode> parseOutline(String raw, int depth) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = stripCodeFence(raw).strip();

        List<ThesisOutlineNode> fromMarkdown = parseMarkdown(text, depth);
        if (usable(fromMarkdown)) {
            return fromMarkdown;
        }
        List<ThesisOutlineNode> fromJson = parseJson(text, depth);
        if (usable(fromJson)) {
            return fromJson;
        }
        List<ThesisOutlineNode> fromIndent = parseIndented(text, depth);
        return usable(fromIndent) ? fromIndent : List.of();
    }

    /** Models wrap their answer in ``` fences even when told not to. */
    private static String stripCodeFence(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int fence = body.lastIndexOf("```");
        return fence >= 0 ? body.substring(0, fence) : body;
    }

    /**
     * A tree needs at least one chapter with at least one section: a single heading is a greeting,
     * not an outline.
     */
    private static boolean usable(List<ThesisOutlineNode> nodes) {
        if (nodes.isEmpty() || nodes.size() > MAX_CHAPTERS) {
            return false;
        }
        return nodes.stream().anyMatch(node -> !node.children().isEmpty());
    }

    private List<ThesisOutlineNode> parseMarkdown(String text, int depth) {
        List<ThesisOutlineNode> roots = new ArrayList<>();
        // stack.get(k) holds the most recent node at level k+1
        List<ThesisOutlineNode> stack = new ArrayList<>();

        for (String rawLine : text.split("\n")) {
            String line = rawLine.strip();
            int level = 0;
            while (level < line.length() && line.charAt(level) == '#') {
                level += 1;
            }
            if (level < 1 || level > 3 || level > depth) {
                continue;
            }
            String title = cleanTitle(line.substring(level));
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

    private List<ThesisOutlineNode> parseJson(String text, int depth) {
        int start = text.indexOf('[');
        int objectStart = text.indexOf('{');
        if (objectStart >= 0 && (start < 0 || objectStart < start)) {
            // {"chapters":[...]} — find the array inside it instead of parsing the wrapper
            int arrayStart = text.indexOf('[', objectStart);
            start = arrayStart >= 0 ? arrayStart : -1;
        }
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(text.substring(start, end + 1));
            if (!array.isArray()) {
                return List.of();
            }
            List<ThesisOutlineNode> nodes = new ArrayList<>();
            for (JsonNode item : array) {
                ThesisOutlineNode node = jsonNode(item, 1, depth);
                if (node != null) {
                    nodes.add(node);
                }
            }
            return nodes;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private ThesisOutlineNode jsonNode(JsonNode item, int level, int depth) {
        if (item == null || level > depth) {
            return null;
        }
        String title = cleanTitle(item.path("title").asText(item.path("name").asText("")));
        if (title.isEmpty()) {
            return null;
        }
        List<ThesisOutlineNode> children = new ArrayList<>();
        JsonNode childArray = item.has("children") ? item.get("children") : item.get("sections");
        if (childArray != null && childArray.isArray()) {
            for (JsonNode child : childArray) {
                ThesisOutlineNode built = jsonNode(child, level + 1, depth);
                if (built != null) {
                    children.add(built);
                }
            }
        }
        return new ThesisOutlineNode(nextId(), title, level, children);
    }

    /**
     * Indented / numbered plain text.
     *
     * <p>Depth comes from leading whitespace; leading ordinals ("1.", "一、", "1.1") are stripped
     * because the client re-numbers every heading it renders.</p>
     */
    private List<ThesisOutlineNode> parseIndented(String text, int depth) {
        List<ThesisOutlineNode> roots = new ArrayList<>();
        List<ThesisOutlineNode> stack = new ArrayList<>();

        for (String rawLine : text.split("\n")) {
            if (rawLine.isBlank()) {
                continue;
            }
            if (rawLine.strip().startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (indent < rawLine.length() && (rawLine.charAt(indent) == ' ' || rawLine.charAt(indent) == '\t')) {
                indent += rawLine.charAt(indent) == '\t' ? 2 : 1;
            }
            // A line with no indentation but an ordinal is still a chapter: models rarely indent.
            int level = indent == 0 ? 1 : Math.min(3, indent / 2 + 1);
            if (level > depth) {
                continue;
            }
            String title = cleanTitle(rawLine.strip());
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

    /** Strips list markers, numbering and trailing punctuation the model likes to add. */
    private static String cleanTitle(String raw) {
        String title = raw
                .replaceAll("^[\\-*+>]+\\s*", "")
                .replaceAll("^\\d+(\\.\\d+)*[\\.、)）]?\\s*", "")
                .replaceAll("^[一二三四五六七八九十]+[、.)）]\\s*", "")
                .replaceAll("[：:]\\s*$", "")
                .replace("**", "")
                .strip();
        if (title.length() > 120) {
            title = title.substring(0, 120);
        }
        return title;
    }

    private static String nextId() {
        return "m_" + System.nanoTime();
    }

    /** Exposed for the catalogue response so the client knows which path will be taken. */
    public String describeMode() {
        return available() ? "MODEL" : "TEMPLATE";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
