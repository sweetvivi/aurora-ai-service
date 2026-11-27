package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.websearch.WebSearchClient;
import com.hub.backend.infra.websearch.WebSearchHit;
import com.hub.backend.modules.auth.dto.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    private static final CurrentUser USER = new CurrentUser(7L, 1L, "demo");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-30T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private WebSearchClient webSearchClient;

    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new WebSearchTool(webSearchClient, new ObjectMapper(), CLOCK);
    }

    @Test
    void failureKeepsModelInstructionsOutOfUserMeta() {
        when(webSearchClient.search(eq("热点"), isNull()))
                .thenThrow(new IllegalStateException("provider down"));

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", WebSearchTool.TOOL_NAME, "{\"query\":\"热点\"}"),
                ToolContext.of(USER));

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("请告知用户"));
        assertTrue(outcome.content().contains("不要编造"));
        assertEquals("联网搜索暂时不可用，请稍后重试", outcome.meta().get("userMessage"));
        assertEquals("unavailable", outcome.meta().get("errorCode"));
        assertFalse(String.valueOf(outcome.meta().get("userMessage")).contains("请告知用户"));
    }

    @Test
    void emptyResultsKeepModelGuidanceInContentOnly() {
        when(webSearchClient.search(eq("冷门词"), isNull())).thenReturn(List.of());

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", WebSearchTool.TOOL_NAME, "{\"query\":\"冷门词\"}"),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("不要编造新闻"));
        assertEquals("未找到相关网页", outcome.meta().get("userMessage"));
        assertEquals(List.of(), outcome.meta().get("results"));
    }

    @Test
    void labelsCrawledAtSeparatelyFromPublishedAt() {
        when(webSearchClient.search(any(), any())).thenReturn(List.of(
                new WebSearchHit("标题A", "https://a.example/1", "摘要A", "站点A", "2026-10-29", ""),
                new WebSearchHit("标题B", "https://b.example/2", "摘要B", "站点B", "", "2026-10-30T01:00:00Z")));

        AgentTool.ToolOutcome outcome = tool.execute(
                new LlmToolCall("c1", WebSearchTool.TOOL_NAME,
                        "{\"query\":\"测试\",\"freshness\":\"oneWeek\"}"),
                ToolContext.of(USER));

        assertTrue(outcome.success());
        assertTrue(outcome.content().contains("发布时间: 2026-10-29"));
        assertTrue(outcome.content().contains("抓取时间（非发布日）: 2026-10-30T01:00:00Z"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) outcome.meta().get("results");
        assertEquals(2, results.size());
        assertEquals("2026-10-29", results.get(0).get("publishedAt"));
        assertEquals("2026-10-30T01:00:00Z", results.get(1).get("crawledAt"));
        assertFalse(results.get(1).containsKey("publishedAt"));
    }
}
