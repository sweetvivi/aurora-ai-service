package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A finished full text.
 *
 * <p>The client renders these sections rather than one blob so chapter headings, the generated
 * reference list and the acknowledgement stay distinguishable — and so "regenerate chapter 3"
 * stays possible without regenerating the rest.</p>
 */
@Schema(name = "ThesisFullTextResponse", description = "生成好的全文")
public record ThesisFullTextResponse(
        @Schema(description = "题目") String topic,
        @Schema(description = "实际使用的模型", example = "deepseek-v4.1-flash") String model,
        @Schema(description = "正文各章") List<Section> sections,
        @Schema(description = "参考文献列表（已核验 DOI）；空数组表示本次没有取到文献") List<Reference> references,
        @Schema(description = "文献来源与筛选口径说明，供前端如实展示", example = "OpenAlex 开放学术索引")
        String referenceSource,
        @Schema(description = "正文总字数（不含附录、参考文献与致谢）") int wordCount,
        @Schema(description = "用户填写的目标字数，用于对照；服务端保证 wordCount 不超过它的 110%",
                example = "8000") int targetWords,
        @Schema(description = "本次消耗的 token 合计") int totalTokens,
        /*
         * 中英文摘要与关键词是论文的必备结构件，不是可选装饰：国内高校的送审格式里
         * 中文摘要、中文关键词、Abstract、Key words 缺一项就会被退回。它们单独成字段
         * 而不是塞进 sections，是因为排版位置固定（目录之前）、且导出时要分页。
         * 空字符串表示这一次没有生成成功，客户端据此提供「生成摘要」按钮。
         */
        @Schema(description = "中文摘要正文（不含「摘要」二字）；空表示未生成") String abstractZh,
        @Schema(description = "中文关键词，以「；」分隔；空表示未生成") String keywordsZh,
        @Schema(description = "英文摘要（不含 Abstract 标题）；空表示未生成") String abstractEn,
        @Schema(description = "英文关键词，以「; 」分隔；空表示未生成") String keywordsEn
) {

    /**
     * 把四个摘要字段统一收成「非 null 字符串」。
     *
     * <p><b>这不是防御性冗余，而是一个真实故障。</b>摘要功能上线前存进库的全文 JSON 里
     * 没有这四个键；Jackson 反序列化到 record 时会把它们填成 {@code null}，
     * 于是 API 返回 `"abstractZh": null`。客户端拿到 null 之后：
     * <ul>
     *   <li>`abstractZh.split(...)` 抛 TypeError，阅读器整页白屏；</li>
     *   <li>`abstractZh.trim()` 同样抛错，连「有没有摘要」的判断都过不去。</li>
     * </ul>
     * 契约里写的是「空字符串表示未生成」，所以必须在出口把 null 归一成空串，
     * 而不是让每个调用方自己记着加 `?.`。</p>
     */
    public ThesisFullTextResponse {
        abstractZh = abstractZh == null ? "" : abstractZh;
        keywordsZh = keywordsZh == null ? "" : keywordsZh;
        abstractEn = abstractEn == null ? "" : abstractEn;
        keywordsEn = keywordsEn == null ? "" : keywordsEn;
    }

    @Schema(name = "ThesisFullTextSection", description = "一章正文")
    public record Section(
            @Schema(description = "章标题", example = "第一章 绪论") String title,
            @Schema(description = "层级，正文章固定为 1", example = "1") int level,
            @Schema(description = "正文，段落以空行分隔") String content
    ) {
    }

    @Schema(name = "ThesisFullTextReference", description = "参考文献条目")
    public record Reference(
            @Schema(description = "序号，正文里的 [n] 与此对应", example = "1") int index,
            @Schema(description = "GB/T 7714 格式的引用文本") String citation,
            @Schema(description = "标题") String title,
            @Schema(description = "DOI，可点击核验") String doi
    ) {
    }
}
