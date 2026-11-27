package com.hub.backend.infra.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.config.properties.WebSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Calls an external web-search API and normalises hits for the chat tool.
 *
 * <p>Supported providers:</p>
 * <ul>
 *   <li>{@code bocha} — {@code POST /v1/web-search} (default, good for Chinese web)</li>
 *   <li>{@code tavily} — {@code POST /search}</li>
 * </ul>
 */
@Component
public class WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(WebSearchClient.class);

    private static final Pattern DAY_HINT = Pattern.compile(
            "今天|今日|今早|今晚|刚刚|昨晚|昨夜|刚才|实时|today|tonight|this\\s+morning|"
                    + "right\\s+now|just\\s+now",
            Pattern.CASE_INSENSITIVE);
    /** Explicit recency window only — not topic words like 新闻/最新 (model passes freshness). */
    private static final Pattern WEEK_HINT = Pattern.compile(
            "本周|这周|近几天|最近几天|这几天|近一周|过去一周|一周内|近日|最近|近期|近来|"
                    + "this\\s+week|past\\s+week|recent(?:ly)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_HINT = Pattern.compile(
            "本月|这个月|近一个月|一个月内|过去一个月|this\\s+month|past\\s+month",
            Pattern.CASE_INSENSITIVE);

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebSearchClient(WebSearchProperties properties,
                           ObjectMapper objectMapper,
                           @Qualifier("llmHttpClient") HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public List<WebSearchHit> search(String query) {
        return search(query, null);
    }

    /**
     * @param freshnessHint optional Bocha-style freshness ({@code oneDay}/{@code oneWeek}/…);
     *                      blank falls back to inference from the query text
     */
    public List<WebSearchHit> search(String query, String freshnessHint) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (!properties.isConfigured()) {
            throw new IllegalStateException(
                    "legacy web search is disabled; use upstream built-in tools:[{type:\"web_search\"}]");
        }

        String provider = normalizeProvider(properties.getProvider());
        String freshness = resolveFreshness(query, freshnessHint);
        return switch (provider) {
            case "tavily" -> searchTavilyWithFallback(query.trim(), freshness);
            case "bocha" -> searchBochaWithFallback(query.trim(), freshness);
            default -> throw new IllegalStateException("unsupported web-search provider: " + provider);
        };
    }

    private List<WebSearchHit> searchBochaWithFallback(String query, String freshness) {
        List<String> attempts = freshnessWidenChain(freshness);
        List<WebSearchHit> hits = List.of();
        for (int i = 0; i < attempts.size(); i++) {
            String attempt = attempts.get(i);
            hits = searchBocha(query, attempt);
            if (!hits.isEmpty() || i == attempts.size() - 1) {
                if (i > 0) {
                    log.info("bocha empty for freshness={}, widened to {} (hits={})",
                            freshness, attempt, hits.size());
                }
                return hits;
            }
        }
        return hits;
    }

    private List<WebSearchHit> searchTavilyWithFallback(String query, String freshness) {
        List<String> attempts = freshnessWidenChain(freshness);
        List<WebSearchHit> hits = List.of();
        for (int i = 0; i < attempts.size(); i++) {
            String attempt = attempts.get(i);
            hits = searchTavily(query, attempt);
            if (!hits.isEmpty() || i == attempts.size() - 1) {
                return hits;
            }
        }
        return hits;
    }

    private List<WebSearchHit> searchBocha(String query, String freshness) {
        int count = clamp(properties.getMaxResults(), 1, 50);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("summary", true);
        body.put("freshness", freshness);
        body.put("count", count);

        String endpoint = requireEndpoint();
        JsonNode root = postJson(endpoint, body, "Bearer " + properties.getApiKey().trim());

        int code = root.path("code").asInt(200);
        if (code != 200) {
            String msg = root.path("msg").asText(root.path("message").asText("bocha search failed"));
            throw new IllegalStateException(msg);
        }

        JsonNode values = root.path("data").path("webPages").path("value");
        List<WebSearchHit> hits = new ArrayList<>();
        if (values.isArray()) {
            for (JsonNode item : values) {
                String title = text(item, "name", "title");
                String url = text(item, "url", "displayUrl");
                String snippet = firstNonBlank(
                        text(item, "summary"),
                        text(item, "snippet"),
                        text(item, "description"));
                String site = text(item, "siteName", "site");
                String publishedAt = firstNonBlank(
                        text(item, "datePublished"),
                        text(item, "datePublishedDisplay"));
                String crawledAt = text(item, "dateLastCrawled");
                if (!StringUtils.hasText(title) && !StringUtils.hasText(url)) {
                    continue;
                }
                hits.add(new WebSearchHit(nullToEmpty(title), nullToEmpty(url),
                        nullToEmpty(snippet), nullToEmpty(site),
                        nullToEmpty(publishedAt), nullToEmpty(crawledAt)));
            }
        }
        return hits;
    }

    private List<WebSearchHit> searchTavily(String query, String freshness) {
        int count = clamp(properties.getMaxResults(), 1, 20);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("api_key", properties.getApiKey().trim());
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("include_answer", false);
        body.put("max_results", count);
        String timeRange = toTavilyTimeRange(freshness);
        if (timeRange != null) {
            body.put("time_range", timeRange);
        }

        String endpoint = requireEndpoint();
        // Tavily authenticates via body api_key; Authorization header is optional.
        JsonNode root = postJson(endpoint, body, null);

        JsonNode results = root.path("results");
        List<WebSearchHit> hits = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode item : results) {
                String title = text(item, "title");
                String url = text(item, "url");
                String snippet = text(item, "content", "snippet");
                String publishedAt = text(item, "published_date", "publishedDate");
                if (!StringUtils.hasText(title) && !StringUtils.hasText(url)) {
                    continue;
                }
                hits.add(new WebSearchHit(nullToEmpty(title), nullToEmpty(url),
                        nullToEmpty(snippet), "", nullToEmpty(publishedAt), ""));
            }
        }
        return hits;
    }

    private String requireEndpoint() {
        String endpoint = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalStateException(
                    "legacy web search base URL is empty; use upstream built-in web_search");
        }
        return endpoint;
    }

    private JsonNode postJson(String endpoint, ObjectNode body, String authorization) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (StringUtils.hasText(authorization)) {
                builder.header("Authorization", authorization);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("web-search HTTP {} from {}: {}", response.statusCode(), endpoint,
                        truncate(response.body()));
                throw new IllegalStateException(
                        "web search HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("web search request failed: " + failure.getMessage(), failure);
        }
    }

    static String resolveFreshness(String query, String freshnessHint) {
        String normalized = normalizeFreshness(freshnessHint);
        if (normalized != null) {
            return normalized;
        }
        return inferFreshness(query);
    }

    static String inferFreshness(String query) {
        if (!StringUtils.hasText(query)) {
            return "noLimit";
        }
        if (DAY_HINT.matcher(query).find()) {
            return "oneDay";
        }
        if (MONTH_HINT.matcher(query).find()) {
            return "oneMonth";
        }
        if (WEEK_HINT.matcher(query).find()) {
            return "oneWeek";
        }
        return "noLimit";
    }

    static String normalizeFreshness(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "oneday", "day", "1d" -> "oneDay";
            case "oneweek", "week", "1w" -> "oneWeek";
            case "onemonth", "month", "1m" -> "oneMonth";
            case "oneyear", "year", "1y" -> "oneYear";
            case "nolimit", "none", "all" -> "noLimit";
            default -> {
                // Allow Bocha date / range forms: 2025-04-06 or 2025-01-01..2025-04-06
                if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}(\\.\\.\\d{4}-\\d{2}-\\d{2})?")) {
                    yield trimmed;
                }
                yield null;
            }
        };
    }

    /** Widen a tight freshness until results appear: oneDay → oneWeek → oneMonth → noLimit. */
    static List<String> freshnessWidenChain(String freshness) {
        String start = StringUtils.hasText(freshness) ? freshness : "noLimit";
        return switch (start) {
            case "oneDay" -> List.of("oneDay", "oneWeek", "oneMonth", "noLimit");
            case "oneWeek" -> List.of("oneWeek", "oneMonth", "noLimit");
            case "oneMonth" -> List.of("oneMonth", "noLimit");
            case "oneYear" -> List.of("oneYear", "noLimit");
            default -> List.of(start);
        };
    }

    private static String toTavilyTimeRange(String freshness) {
        if (!StringUtils.hasText(freshness)) {
            return null;
        }
        return switch (freshness) {
            case "oneDay" -> "day";
            case "oneWeek" -> "week";
            case "oneMonth" -> "month";
            case "oneYear" -> "year";
            default -> null;
        };
    }

    private static String normalizeProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return "bocha";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 240 ? body : body.substring(0, 240) + "…";
    }
}
