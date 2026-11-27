package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.infra.websearch.WebSearchClient;
import com.hub.backend.infra.websearch.WebSearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legacy Bocha/Tavily function-tool implementation.
 *
 * <p><b>Deprecated for Chat/Agent orchestration.</b> General chat and agents now use the
 * upstream built-in {@code tools:[{type:"web_search"}]} when the catalogue marks
 * {@code supportsWebSearch}. This bean remains registered so historical ability keys resolve,
 * but {@code AgentChatService} strips it from function tools and never executes it.</p>
 *
 * @deprecated Prefer native gateway web search; do not inject this tool from ChatService.
 */
@Deprecated
@Component
public class WebSearchTool implements AgentTool {

    public static final String TOOL_NAME = "web_search";

    private static final int MAX_QUERY = 500;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final WebSearchClient webSearchClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebSearchTool(WebSearchClient webSearchClient, ObjectMapper objectMapper, Clock clock) {
        this.webSearchClient = webSearchClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public LlmToolDefinition definition() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        String iso = today.format(ISO_DATE);
        String cn = today.format(CN_DATE);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "搜索关键词。今日为 " + cn + "（" + iso + "）。"
                        + "用户说「今天/今日」时，请把该准确日期写进关键词（如「" + cn + " 热点新闻」），"
                        + "不要用训练知识里的过时日期；保留「新闻」等意图词；「最近一周/本月」可保留相对表述。"));
        properties.put("freshness", Map.of(
                "type", "string",
                "description", "可选时间范围：oneDay / oneWeek / oneMonth / oneYear / noLimit，"
                        + "或精确日期 " + iso + " / 区间 YYYY-MM-DD..YYYY-MM-DD。"
                        + "问「今天」「刚刚」用 oneDay 或 " + iso + "；「最近」「本周」或时效新闻用 oneWeek；"
                        + "不确定可省略。主题词（如「新闻学」）不要单独当作时效窗口。"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "联网搜索。今日（亚洲/上海）为 " + cn + "（" + iso + "）。"
                        + "用户已开启联网时，凡涉及新闻、时事、价格、政策、最新数据、人物近况或需要核对事实，"
                        + "必须先调用本工具再作答；不要仅凭训练知识编造时效内容。"
                        + "返回网页标题、链接、摘要与发布时间，请严格依据结果作答并注明来源；"
                        + "对照今日日期判断时效，摘要未写明的细节不要臆测。"
                        + "若仅有抓取时间而无发布时间，勿把抓取日当作发布日。",
                schema);
    }

    @Override
    public boolean enabledByDefault() {
        // 通用对话由 ChatService 按开关注入；智能体需 abilityKeys 显式开通
        return false;
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        String query;
        String freshness;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            JsonNode args = objectMapper.readTree(raw);
            query = args.path("query").asText("").trim();
            freshness = args.path("freshness").asText("").trim();
            if (freshness.isEmpty()) {
                freshness = null;
            }
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed(
                    "工具参数不是合法 JSON：" + malformed.getOriginalMessage(),
                    userFacing("参数错误，请换个说法重试", "invalid_input"));
        }

        if (query.isBlank()) {
            return ToolOutcome.failed(
                    "缺少 query 参数，请给出搜索关键词",
                    userFacing("缺少搜索关键词", "invalid_input"));
        }
        if (query.length() > MAX_QUERY) {
            query = query.substring(0, MAX_QUERY);
        }

        try {
            List<WebSearchHit> hits = webSearchClient.search(query, freshness);
            if (hits.isEmpty()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("query", query);
                meta.put("results", List.of());
                meta.put("userMessage", "未找到相关网页");
                return ToolOutcome.ok("未找到与「" + query + "」相关的网页结果。请换关键词重试，"
                                + "或如实告知用户暂无检索结果，不要编造新闻。",
                        meta);
            }

            LocalDate today = LocalDate.now(clock.withZone(ZONE));
            String iso = today.format(ISO_DATE);
            List<Map<String, Object>> results = new ArrayList<>(hits.size());
            StringBuilder forModel = new StringBuilder();
            forModel.append("今天是 ").append(today.format(CN_DATE)).append("（").append(iso).append("）。")
                    .append("搜索「").append(query).append("」共 ").append(hits.size()).append(" 条结果。")
                    .append("请只依据下列摘要作答，未写明的细节勿臆测；注明来源链接；")
                    .append("用发布时间对照今日 ").append(iso)
                    .append("：若多为昨日或更早，须如实说明，不要当作「今天」新闻。")
                    .append("仅有抓取时间时勿当作发布日。\n");
            int index = 1;
            for (WebSearchHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", hit.title());
                item.put("url", hit.url());
                item.put("snippet", hit.snippet());
                if (hit.site() != null && !hit.site().isBlank()) {
                    item.put("site", hit.site());
                }
                if (hit.publishedAt() != null && !hit.publishedAt().isBlank()) {
                    item.put("publishedAt", hit.publishedAt());
                }
                if (hit.crawledAt() != null && !hit.crawledAt().isBlank()) {
                    item.put("crawledAt", hit.crawledAt());
                }
                results.add(item);

                forModel.append(index++).append(". ").append(hit.title()).append('\n');
                if (hit.publishedAt() != null && !hit.publishedAt().isBlank()) {
                    forModel.append("   发布时间: ").append(hit.publishedAt()).append('\n');
                } else if (hit.crawledAt() != null && !hit.crawledAt().isBlank()) {
                    forModel.append("   抓取时间（非发布日）: ").append(hit.crawledAt()).append('\n');
                }
                if (hit.site() != null && !hit.site().isBlank()) {
                    forModel.append("   来源: ").append(hit.site()).append('\n');
                }
                if (hit.url() != null && !hit.url().isBlank()) {
                    forModel.append("   链接: ").append(hit.url()).append('\n');
                }
                if (hit.snippet() != null && !hit.snippet().isBlank()) {
                    forModel.append("   摘要: ").append(hit.snippet()).append('\n');
                }
            }

            log.info("web_search query='{}' freshness={} hits={} user={}",
                    query, freshness,
                    hits.size(),
                    context.currentUser() == null ? null : context.currentUser().userId());
            return ToolOutcome.ok(forModel.toString(), Map.of("query", query, "results", results));
        } catch (Exception failure) {
            log.warn("web_search failed: {}", failure.getMessage());
            String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "upstream error"
                    : failure.getMessage();
            return ToolOutcome.failed(
                    "联网搜索失败：" + reason + "。请告知用户本次联网未成功，不要编造搜索结果。",
                    userFacing("联网搜索暂时不可用，请稍后重试", "unavailable"));
        }
    }

    private static Map<String, Object> userFacing(String userMessage, String errorCode) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("userMessage", userMessage);
        meta.put("errorCode", errorCode);
        return meta;
    }
}
