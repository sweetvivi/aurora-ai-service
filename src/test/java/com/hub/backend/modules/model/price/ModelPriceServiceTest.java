package com.hub.backend.modules.model.price;

import com.hub.backend.infra.newapi.NewApiModelPlaza;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceServiceTest {

    @Test
    void aggregatesTheLowestAnonymousPriceAcrossPublicGroups() {
        NewApiModelPlaza plaza = plaza(
                group(1L, "standard", model("gpt-5", pricing(
                        "token", "0.000002", "0.000008", null, null))),
                group(2L, "discount", model(" gpt-5 ", pricing(
                        "token", "0.000001", "0.000004", null, null))));

        Map<String, ModelPriceView> prices = ModelPriceService.aggregate(plaza);

        assertThat(prices).containsOnlyKeys("gpt-5");
        ModelPriceView view = prices.get("gpt-5");
        assertThat(view.mode()).isEqualTo(ModelPriceView.MODE_TOKEN);
        assertThat(view.inputUsdPerMillion()).isEqualByComparingTo("1");
        assertThat(view.outputUsdPerMillion()).isEqualByComparingTo("4");
        assertThat(view.sortPrice()).isEqualByComparingTo("1");
    }

    @Test
    void keepsZeroAsAValidFreePriceAndIgnoresNull() {
        NewApiModelPlaza plaza = plaza(
                group(1L, "paid", model("free-model", pricing(
                        "token", "0.000003", "0.000004", null, null))),
                group(2L, "free", model("free-model", pricing(
                        "token", "0", null, null, null))));

        ModelPriceView view = ModelPriceService.aggregate(plaza).get("free-model");

        assertThat(view.inputUsdPerMillion()).isEqualByComparingTo("0");
        assertThat(view.outputUsdPerMillion()).isEqualByComparingTo("4");
        assertThat(view.sortPrice()).isEqualByComparingTo("0");
    }

    @Test
    void scalesTokenCacheAndImagePricesButNotPerRequestPrices() {
        NewApiModelPlaza.Pricing pricing = new NewApiModelPlaza.Pricing(
                "tiered_expr",
                bd("0.000001"),
                bd("0.000002"),
                bd("0.000003"),
                bd("0.000004"),
                bd("0.0000005"),
                bd("0.000006"),
                bd("0.000007"),
                bd("0.25"),
                List.of());
        NewApiModelPlaza plaza = plaza(
                group(1L, "image", model("image-model", pricing)));

        ModelPriceView view = ModelPriceService.aggregate(plaza).get("image-model");

        assertThat(view.mode()).isEqualTo(ModelPriceView.MODE_EXPRESSION);
        assertThat(view.inputUsdPerMillion()).isEqualByComparingTo("1");
        assertThat(view.outputUsdPerMillion()).isEqualByComparingTo("2");
        assertThat(view.cacheWriteUsdPerMillion()).isEqualByComparingTo("3");
        assertThat(view.cacheWrite1hUsdPerMillion()).isEqualByComparingTo("4");
        assertThat(view.cacheReadUsdPerMillion()).isEqualByComparingTo("0.5");
        assertThat(view.imageInputUsdPerMillion()).isEqualByComparingTo("6");
        assertThat(view.imageOutputUsdPerMillion()).isEqualByComparingTo("7");
        assertThat(view.usdPerRequest()).isEqualByComparingTo("0.25");
    }

    @Test
    void aggregatesTieredTokenPricesAndSortsIntervals() {
        NewApiModelPlaza.Interval second = interval(
                100_001L, null, "0.000004", "0.000008", "0.5");
        NewApiModelPlaza.Interval first = interval(
                0L, 100_000L, "0.000002", "0.000006", null);
        NewApiModelPlaza.Pricing pricing = new NewApiModelPlaza.Pricing(
                "tiered", null, null, null, null, null, null, null, null,
                List.of(second, first));
        NewApiModelPlaza plaza = plaza(
                group(1L, "tiers", model("tiered-model", pricing)));

        ModelPriceView view = ModelPriceService.aggregate(plaza).get("tiered-model");

        assertThat(view.mode()).isEqualTo(ModelPriceView.MODE_EXPRESSION);
        assertThat(view.intervals()).hasSize(2);
        assertThat(view.intervals().get(0).minTokens()).isZero();
        assertThat(view.intervals().get(0).inputUsdPerMillion())
                .isEqualByComparingTo("2");
        assertThat(view.intervals().get(1).minTokens()).isEqualTo(100_001L);
        assertThat(view.intervals().get(1).usdPerRequest())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void reportsUnpricedModelsWithNoSortValue() {
        NewApiModelPlaza plaza = plaza(
                group(1L, "unpriced", model("mystery", pricing(
                        null, null, null, null, null))));

        ModelPriceView view = ModelPriceService.aggregate(plaza).get("mystery");

        assertThat(view.mode()).isEqualTo(ModelPriceView.MODE_UNPRICED);
        assertThat(view.sortPrice()).isNull();
    }

    @Test
    void preservesPlazaAndModelOrderInTheAggregateMap() {
        NewApiModelPlaza plaza = plaza(
                group(1L, "first",
                        model("z-model", pricing("token", "0.000001", null, null, null)),
                        model("a-model", pricing("token", "0.000002", null, null, null))),
                group(2L, "second",
                        model("m-model", pricing("token", "0.000003", null, null, null))));

        assertThat(ModelPriceService.aggregate(plaza).keySet())
                .containsExactly("z-model", "a-model", "m-model");
    }

    @Test
    void groupsByModelKeyKeepsEveryUpstreamGroupInDeclarationOrder() {
        NewApiModelPlaza plaza = plaza(
                group(1L, "default",
                        model("gpt-5", pricing("token", "0.000002", null, null, null)),
                        model("seedance", pricing("token", "0.000003", null, null, null))),
                group(2L, "vip",
                        model("gpt-5", pricing("token", "0.000004", null, null, null))),
                group(3L, "svip",
                        model("gpt-5", pricing("token", "0.000005", null, null, null))));

        Map<String, List<String>> groups = ModelPriceService.groupsByModelKey(plaza);

        assertThat(groups).containsOnlyKeys("gpt-5", "seedance");
        // 一个模型可以在多个分组里，顺序与上游声明一致
        assertThat(groups.get("gpt-5")).containsExactly("default", "vip", "svip");
        assertThat(groups.get("seedance")).containsExactly("default");
    }

    @Test
    void groupsByModelKeyTrimsNamesAndIgnoresBlankGroups() {
        NewApiModelPlaza plaza = plaza(
                group(1L, "  default  ",
                        model(" gpt-5 ", pricing("token", "0.000002", null, null, null))),
                group(2L, "   ",
                        model("gpt-5", pricing("token", "0.000002", null, null, null))));

        Map<String, List<String>> groups = ModelPriceService.groupsByModelKey(plaza);

        // 模型名与分组名都按 strip 后的值归并，空白分组整组跳过
        assertThat(groups).containsOnlyKeys("gpt-5");
        assertThat(groups.get("gpt-5")).containsExactly("default");
    }

    @Test
    void groupsByModelKeyReturnsEmptyMapForEmptyPlaza() {
        assertThat(ModelPriceService.groupsByModelKey(
                new NewApiModelPlaza("empty", List.of()))).isEmpty();
        assertThat(ModelPriceService.groupsByModelKey(null)).isEmpty();
    }

    private static NewApiModelPlaza plaza(NewApiModelPlaza.Group... groups) {
        return new NewApiModelPlaza("public models", List.of(groups));
    }

    private static NewApiModelPlaza.Group group(
            Long id,
            String name,
            NewApiModelPlaza.Model... models) {
        return new NewApiModelPlaza.Group(id, name, "openai", List.of(models));
    }

    private static NewApiModelPlaza.Model model(
            String name,
            NewApiModelPlaza.Pricing pricing) {
        return new NewApiModelPlaza.Model(name, "openai", pricing);
    }

    private static NewApiModelPlaza.Pricing pricing(
            String mode,
            String input,
            String output,
            String cacheRead,
            String perRequest) {
        return new NewApiModelPlaza.Pricing(
                mode,
                bd(input),
                bd(output),
                null,
                null,
                bd(cacheRead),
                null,
                null,
                bd(perRequest),
                List.of());
    }

    private static NewApiModelPlaza.Interval interval(
            Long min,
            Long max,
            String input,
            String output,
            String perRequest) {
        return new NewApiModelPlaza.Interval(
                min, max, bd(input), bd(output), null, null, null, bd(perRequest));
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
