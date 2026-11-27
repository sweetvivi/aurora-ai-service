package com.hub.backend.modules.thesis.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 全文响应的兼容性约束。
 *
 * <p>摘要四个字段是**后加**的。功能上线前存进库的全文 JSON 里没有这些键，
 * 用 Jackson 反序列化到 record 时它们会变成 `null`，然后原样被 API 返回。
 * 客户端拿到 null 后做 `.split()` / `.trim()` 会抛 TypeError —— 表现是
 * 「打开旧稿件，阅读器白屏」，而新生成的稿件永远复现不出来。</p>
 *
 * <p>所以契约必须是「这四个字段永远是非空字符串」，由 record 的紧凑构造器保证，
 * 而不是要求每个调用方自己加 `?.`。</p>
 */
class ThesisFullTextResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void legacyJsonWithoutAbstractKeysYieldsEmptyStringsNotNull() throws Exception {
        // 这就是库里 2026-11 之前存下的 JSON 形状：没有摘要字段
        String legacy = """
                {"topic":"旧稿件","model":"m","sections":[],"references":[],
                 "referenceSource":"未启用","wordCount":100,"targetWords":100,"totalTokens":1}
                """;

        ThesisFullTextResponse parsed = mapper.readValue(legacy, ThesisFullTextResponse.class);

        assertEquals("", parsed.abstractZh());
        assertEquals("", parsed.keywordsZh());
        assertEquals("", parsed.abstractEn());
        assertEquals("", parsed.keywordsEn());
    }

    @Test
    void explicitNullsAreNormalisedToo() throws Exception {
        String withNulls = """
                {"topic":"半旧稿件","model":"m","sections":[],"references":[],
                 "referenceSource":"","wordCount":0,"targetWords":0,"totalTokens":0,
                 "abstractZh":null,"keywordsZh":null,"abstractEn":null,"keywordsEn":null}
                """;

        ThesisFullTextResponse parsed = mapper.readValue(withNulls, ThesisFullTextResponse.class);

        assertNotNull(parsed.abstractZh());
        assertEquals("", parsed.abstractEn());
    }

    @Test
    void realAbstractsSurviveTheRoundTrip() throws Exception {
        ThesisFullTextResponse original = new ThesisFullTextResponse(
                "题目", "m", List.of(), List.of(), "未启用", 100, 100, 1,
                "中文摘要。", "关键词一；关键词二", "Abstract body.", "kw1; kw2");

        ThesisFullTextResponse parsed = mapper.readValue(
                mapper.writeValueAsString(original), ThesisFullTextResponse.class);

        assertEquals("中文摘要。", parsed.abstractZh());
        assertEquals("关键词一；关键词二", parsed.keywordsZh());
        assertEquals("Abstract body.", parsed.abstractEn());
        assertEquals("kw1; kw2", parsed.keywordsEn());
    }
}