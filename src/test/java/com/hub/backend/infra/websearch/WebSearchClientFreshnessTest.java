package com.hub.backend.infra.websearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebSearchClientFreshnessTest {

    @Test
    void infersOneDayFromTodayHints() {
        assertEquals("oneDay", WebSearchClient.inferFreshness("今天有什么新闻"));
        assertEquals("oneDay", WebSearchClient.inferFreshness("今日热点"));
        assertEquals("oneDay", WebSearchClient.inferFreshness("what happened today"));
    }

    @Test
    void infersOneWeekFromExplicitRecencyHints() {
        assertEquals("oneWeek", WebSearchClient.inferFreshness("最近科技动态"));
        assertEquals("oneWeek", WebSearchClient.inferFreshness("本周要闻"));
        assertEquals("oneWeek", WebSearchClient.inferFreshness("recent AI updates"));
    }

    @Test
    void doesNotInferWeekFromTopicWordsAlone() {
        assertEquals("noLimit", WebSearchClient.inferFreshness("国际新闻"));
        assertEquals("noLimit", WebSearchClient.inferFreshness("什么是新闻学"));
        assertEquals("noLimit", WebSearchClient.inferFreshness("latest fashion trends meaning"));
    }

    @Test
    void infersOneMonthFromMonthHints() {
        assertEquals("oneMonth", WebSearchClient.inferFreshness("本月经济数据"));
    }

    @Test
    void defaultsToNoLimitWithoutTimeHints() {
        assertEquals("noLimit", WebSearchClient.inferFreshness("什么是光合作用"));
    }

    @Test
    void prefersExplicitFreshnessHint() {
        assertEquals("oneWeek", WebSearchClient.resolveFreshness("今天新闻", "oneWeek"));
        assertEquals("oneDay", WebSearchClient.resolveFreshness("随便", "day"));
        assertEquals("2025-04-06", WebSearchClient.resolveFreshness("x", "2025-04-06"));
        assertNull(WebSearchClient.normalizeFreshness("not-a-value"));
    }

    @Test
    void widensFreshnessChain() {
        assertEquals(List.of("oneDay", "oneWeek", "oneMonth", "noLimit"),
                WebSearchClient.freshnessWidenChain("oneDay"));
        assertEquals(List.of("oneWeek", "oneMonth", "noLimit"),
                WebSearchClient.freshnessWidenChain("oneWeek"));
        assertEquals(List.of("noLimit"), WebSearchClient.freshnessWidenChain("noLimit"));
    }
}
