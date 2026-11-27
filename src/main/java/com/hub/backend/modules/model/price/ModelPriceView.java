package com.hub.backend.modules.model.price;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个模型在 New API 侧的实际计价方式与价格。
 *
 * <p>后台「有效价格」直接展示它，口径与 New API 定价页保持一致：</p>
 *
 * <ul>
 *   <li>{@code TOKEN}：{@code inputUsdPerMillion} / {@code outputUsdPerMillion} 是 USD/百万 tokens，
 *       与 New API 的 {@code ModelRatio × 2} 规则一致；</li>
 *   <li>{@code EXPRESSION}：New API 的 tier 表达式计费（图片模型常见），表达式里的 {@code p}、
 *       {@code c} 系数同样折算成 USD/百万 tokens；</li>
 *   <li>{@code PER_REQUEST}：{@code usdPerRequest} 是 USD/次；</li>
 *   <li>{@code UNPRICED}：New API 里没有配置价格。</li>
 * </ul>
 *
 * @param sortPrice 只服务于「按价格从低到高」的组内排序；无法比价时为 {@code null}，排在最后
 */
public record ModelPriceView(
        String mode,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion,
        BigDecimal usdPerRequest,
        BigDecimal sortPrice,
        String billingMode,
        BigDecimal cacheWriteUsdPerMillion,
        BigDecimal cacheWrite1hUsdPerMillion,
        BigDecimal cacheReadUsdPerMillion,
        BigDecimal imageInputUsdPerMillion,
        BigDecimal imageOutputUsdPerMillion,
        List<IntervalPriceView> intervals) {

    public static final String MODE_TOKEN = "TOKEN";
    public static final String MODE_EXPRESSION = "EXPRESSION";
    public static final String MODE_PER_REQUEST = "PER_REQUEST";
    public static final String MODE_UNPRICED = "UNPRICED";

    public ModelPriceView {
        intervals = intervals == null ? List.of() : List.copyOf(intervals);
    }

    /** Keeps the original five-field contract for callers that do not display cache or image prices. */
    public ModelPriceView(String mode,
                          BigDecimal inputUsdPerMillion,
                          BigDecimal outputUsdPerMillion,
                          BigDecimal usdPerRequest,
                          BigDecimal sortPrice) {
        this(mode, inputUsdPerMillion, outputUsdPerMillion, usdPerRequest, sortPrice,
                null, null, null, null, null, null, List.of());
    }

    public static ModelPriceView unpriced() {
        return new ModelPriceView(MODE_UNPRICED, null, null, null, null);
    }

    /**
     * One price tier from the anonymous model plaza. Token, cache and image fields are USD per
     * million tokens; {@code usdPerRequest} is USD per request.
     */
    public record IntervalPriceView(
            Long minTokens,
            Long maxTokens,
            BigDecimal inputUsdPerMillion,
            BigDecimal outputUsdPerMillion,
            BigDecimal cacheWriteUsdPerMillion,
            BigDecimal cacheWrite1hUsdPerMillion,
            BigDecimal cacheReadUsdPerMillion,
            BigDecimal usdPerRequest,
            BigDecimal sortPrice) {
    }
}
