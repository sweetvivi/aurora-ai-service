package com.hub.backend.modules.model.price;

import com.hub.backend.infra.newapi.NewApiManagementClient;
import com.hub.backend.infra.newapi.NewApiModelPlaza;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the anonymous New API model plaza and exposes one price view per public model name.
 *
 * <p>A model can be advertised in several public groups. For every field this service keeps the
 * lowest non-null value, so {@code 0} is a real free price while {@code null} means that the field
 * was not advertised. Token, cache, image and tiered-token prices are converted from USD/token to
 * USD/million tokens; per-request prices stay unchanged.</p>
 */
@Service
public class ModelPriceService {

    private static final Logger log = LoggerFactory.getLogger(ModelPriceService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final NewApiManagementClient newApi;

    private volatile Snapshot cache = new Snapshot(Map.of(), Map.of(), Instant.EPOCH);

    public ModelPriceService(NewApiManagementClient newApi) {
        this.newApi = newApi;
    }

    /** model name -> the lowest anonymously advertised price across all public groups. */
    public Map<String, ModelPriceView> pricesByModelKey() {
        return snapshot().prices();
    }

    /**
     * model name -> the New API groups that advertise it（{@code enable_groups} 的原始顺序）。
     *
     * <p>只服务于后台「这个模型在上游属于哪个分组」的展示：分组决定价格倍率，
     * 运营挑模型时需要知道它会不会因为分组倍率而变贵。空列表表示上游没声明分组。</p>
     */
    public Map<String, List<String>> upstreamGroupsByModelKey() {
        return snapshot().groups();
    }

    /**
     * 一次上游抓取同时产出价格与分组。
     *
     * <p>两者必须来自同一份 {@code /api/pricing} 响应：分开抓会出现「价格是这一版、
     * 分组是上一版」的错位，而且各自缓存还会有两次超时。</p>
     */
    private Snapshot snapshot() {
        Snapshot current = cache;
        if (current.isFresh()) {
            return current;
        }
        try {
            NewApiModelPlaza plaza = newApi.fetchModelPlaza();
            Map<String, ModelPriceView> prices = aggregate(plaza);
            if (prices.isEmpty()) {
                log.warn("New API anonymous model plaza published no models; keeping the last "
                        + "price snapshot");
                cache = new Snapshot(current.prices(), current.groups(), Instant.now());
                return cache;
            }
            Map<String, ModelPriceView> priceSnapshot =
                    Collections.unmodifiableMap(prices);
            Map<String, List<String>> groupSnapshot =
                    Collections.unmodifiableMap(groupsByModelKey(plaza));
            cache = new Snapshot(priceSnapshot, groupSnapshot, Instant.now());
            return cache;
        } catch (RuntimeException exception) {
            log.warn("New API anonymous model plaza is unavailable ({}); keeping the last price "
                    + "snapshot", failureSummary(exception));
            // Advance the timestamp so every admin refresh does not wait for the same timeout.
            cache = new Snapshot(current.prices(), current.groups(), Instant.now());
            return cache;
        }
    }

    public ModelPriceView priceOf(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return ModelPriceView.unpriced();
        }
        return pricesByModelKey().getOrDefault(modelKey.strip(), ModelPriceView.unpriced());
    }

    public static Map<String, ModelPriceView> aggregate(NewApiModelPlaza plaza) {
        if (plaza == null || plaza.groups().isEmpty()) {
            return Map.of();
        }

        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        for (NewApiModelPlaza.Group group : plaza.groups()) {
            for (NewApiModelPlaza.Model model : group.models()) {
                if (model == null || model.name() == null || model.name().isBlank()) {
                    continue;
                }
                accumulators.computeIfAbsent(
                                model.name().strip(), ignored -> new Accumulator())
                        .add(model.pricing());
            }
        }

        Map<String, ModelPriceView> prices = new LinkedHashMap<>();
        accumulators.forEach((name, accumulator) -> prices.put(name, accumulator.toView()));
        return Collections.unmodifiableMap(prices);
    }

    /**
     * 从同一份广场数据里提取「模型名 -> 所属上游分组」。
     *
     * <p>一个模型可以同时在多个分组里（例如 default 与 vip），按出现顺序去重保留，
     * 让后台展示的顺序和上游 {@code enable_groups} 一致。同名模型在组内的重复条目只留一个。</p>
     */
    public static Map<String, List<String>> groupsByModelKey(NewApiModelPlaza plaza) {
        if (plaza == null || plaza.groups().isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> collected = new LinkedHashMap<>();
        for (NewApiModelPlaza.Group group : plaza.groups()) {
            String groupName = group.name();
            if (groupName == null || groupName.isBlank()) {
                continue;
            }
            String normalized = groupName.strip();
            for (NewApiModelPlaza.Model model : group.models()) {
                if (model == null || model.name() == null || model.name().isBlank()) {
                    continue;
                }
                collected
                        .computeIfAbsent(model.name().strip(), ignored -> new LinkedHashSet<>())
                        .add(normalized);
            }
        }

        Map<String, List<String>> groups = new LinkedHashMap<>();
        collected.forEach((name, names) -> groups.put(name, List.copyOf(names)));
        return Collections.unmodifiableMap(groups);
    }

    private static String failureSummary(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private record Snapshot(Map<String, ModelPriceView> prices,
                            Map<String, List<String>> groups,
                            Instant fetchedAt) {

        boolean isFresh() {
            return fetchedAt.plus(CACHE_TTL).isAfter(Instant.now());
        }
    }

    private static final class Accumulator {

        private String billingMode;
        private BigDecimal inputPrice;
        private BigDecimal outputPrice;
        private BigDecimal cacheWritePrice;
        private BigDecimal cacheWrite1hPrice;
        private BigDecimal cacheReadPrice;
        private BigDecimal imageInputPrice;
        private BigDecimal imageOutputPrice;
        private BigDecimal perRequestPrice;
        private final Map<IntervalKey, IntervalAccumulator> intervals = new LinkedHashMap<>();

        void add(NewApiModelPlaza.Pricing pricing) {
            if (pricing == null) {
                return;
            }
            if (billingMode == null && pricing.billingMode() != null
                    && !pricing.billingMode().isBlank()) {
                billingMode = pricing.billingMode().strip();
            }
            inputPrice = lower(inputPrice, pricing.inputPrice());
            outputPrice = lower(outputPrice, pricing.outputPrice());
            cacheWritePrice = lower(cacheWritePrice, pricing.cacheWritePrice());
            cacheWrite1hPrice = lower(cacheWrite1hPrice, pricing.cacheWrite1hPrice());
            cacheReadPrice = lower(cacheReadPrice, pricing.cacheReadPrice());
            imageInputPrice = lower(imageInputPrice, pricing.imageInputPrice());
            imageOutputPrice = lower(imageOutputPrice, pricing.imageOutputPrice());
            perRequestPrice = lower(perRequestPrice, pricing.perRequestPrice());
            for (NewApiModelPlaza.Interval interval : pricing.intervals()) {
                if (interval == null) {
                    continue;
                }
                intervals.computeIfAbsent(
                                new IntervalKey(interval.minTokens(), interval.maxTokens()),
                                ignored -> new IntervalAccumulator())
                        .add(interval);
            }
        }

        ModelPriceView toView() {
            BigDecimal input = scaled(inputPrice);
            BigDecimal output = scaled(outputPrice);
            BigDecimal cacheWrite = scaled(cacheWritePrice);
            BigDecimal cacheWrite1h = scaled(cacheWrite1hPrice);
            BigDecimal cacheRead = scaled(cacheReadPrice);
            BigDecimal imageInput = scaled(imageInputPrice);
            BigDecimal imageOutput = scaled(imageOutputPrice);
            BigDecimal request = perRequestPrice;

            List<ModelPriceView.IntervalPriceView> intervalViews = intervals.entrySet().stream()
                    .map(entry -> entry.getValue().toView(entry.getKey()))
                    .sorted(Comparator
                            .comparing(
                                    ModelPriceView.IntervalPriceView::minTokens,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(
                                    ModelPriceView.IntervalPriceView::maxTokens,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            Set<BigDecimal> sortable = new LinkedHashSet<>();
            addAll(sortable, input, output, cacheWrite, cacheWrite1h, cacheRead,
                    imageInput, imageOutput, request);
            intervalViews.forEach(interval -> addAll(sortable,
                    interval.inputUsdPerMillion(),
                    interval.outputUsdPerMillion(),
                    interval.cacheWriteUsdPerMillion(),
                    interval.cacheWrite1hUsdPerMillion(),
                    interval.cacheReadUsdPerMillion(),
                    interval.usdPerRequest()));
            BigDecimal sortPrice = sortable.stream()
                    .min(Comparator.naturalOrder())
                    .orElse(null);

            boolean hasTokenPrice = anyNonNull(
                    input, output, cacheWrite, cacheWrite1h, cacheRead, imageInput, imageOutput)
                    || intervalViews.stream().anyMatch(interval -> anyNonNull(
                            interval.inputUsdPerMillion(),
                            interval.outputUsdPerMillion(),
                            interval.cacheWriteUsdPerMillion(),
                            interval.cacheWrite1hUsdPerMillion(),
                            interval.cacheReadUsdPerMillion()));
            boolean hasRequestPrice = request != null || intervalViews.stream()
                    .anyMatch(interval -> interval.usdPerRequest() != null);
            String mode = hasTokenPrice
                    ? expressionMode(billingMode)
                    : hasRequestPrice
                    ? ModelPriceView.MODE_PER_REQUEST
                    : ModelPriceView.MODE_UNPRICED;

            return new ModelPriceView(
                    mode,
                    input,
                    output,
                    request,
                    sortPrice,
                    billingMode,
                    cacheWrite,
                    cacheWrite1h,
                    cacheRead,
                    imageInput,
                    imageOutput,
                    intervalViews);
        }

        private static String expressionMode(String mode) {
            String normalized = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
            return normalized.contains("tier") || normalized.contains("expr")
                    ? ModelPriceView.MODE_EXPRESSION
                    : ModelPriceView.MODE_TOKEN;
        }
    }

    private static final class IntervalAccumulator {

        private BigDecimal inputPrice;
        private BigDecimal outputPrice;
        private BigDecimal cacheWritePrice;
        private BigDecimal cacheWrite1hPrice;
        private BigDecimal cacheReadPrice;
        private BigDecimal perRequestPrice;

        void add(NewApiModelPlaza.Interval interval) {
            inputPrice = lower(inputPrice, interval.inputPrice());
            outputPrice = lower(outputPrice, interval.outputPrice());
            cacheWritePrice = lower(cacheWritePrice, interval.cacheWritePrice());
            cacheWrite1hPrice = lower(cacheWrite1hPrice, interval.cacheWrite1hPrice());
            cacheReadPrice = lower(cacheReadPrice, interval.cacheReadPrice());
            perRequestPrice = lower(perRequestPrice, interval.perRequestPrice());
        }

        ModelPriceView.IntervalPriceView toView(IntervalKey key) {
            BigDecimal input = scaled(inputPrice);
            BigDecimal output = scaled(outputPrice);
            BigDecimal cacheWrite = scaled(cacheWritePrice);
            BigDecimal cacheWrite1h = scaled(cacheWrite1hPrice);
            BigDecimal cacheRead = scaled(cacheReadPrice);
            List<BigDecimal> sortable = new ArrayList<>(7);
            addAll(sortable, input, output, cacheWrite, cacheWrite1h, cacheRead, perRequestPrice);
            BigDecimal sortPrice = sortable.stream()
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            return new ModelPriceView.IntervalPriceView(
                    key.minTokens(),
                    key.maxTokens(),
                    input,
                    output,
                    cacheWrite,
                    cacheWrite1h,
                    cacheRead,
                    perRequestPrice,
                    sortPrice);
        }
    }

    private record IntervalKey(Long minTokens, Long maxTokens) {
    }

    private static BigDecimal lower(BigDecimal current, BigDecimal candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.multiply(ONE_MILLION);
    }

    private static boolean anyNonNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(Set<BigDecimal> target, BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                target.add(value);
            }
        }
    }

    private static void addAll(List<BigDecimal> target, BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) {
                target.add(value);
            }
        }
    }
}
