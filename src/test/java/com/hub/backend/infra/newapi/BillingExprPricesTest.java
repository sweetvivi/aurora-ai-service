package com.hub.backend.infra.newapi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New API publishes {@code billing_expr} instead of a tier table. The coefficients of that
 * expression are USD per million tokens, so they can be read back exactly.
 */
class BillingExprPricesTest {

    @Test
    void readsTheCoefficientsOfASingleTier() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "tier(\"standard\", p * 5 + c * 30)");

        assertThat(tiers).hasSize(1);
        BillingExprPrices.Coefficients coefficients = tiers.get(0).coefficients();
        assertThat(tiers.get(0).minTokens()).isZero();
        assertThat(tiers.get(0).maxTokens()).isNull();
        assertThat(coefficients.input()).isEqualByComparingTo("5");
        assertThat(coefficients.output()).isEqualByComparingTo("30");
        assertThat(coefficients.cacheRead()).isNull();
        assertThat(coefficients.perRequest()).isNull();
    }

    @Test
    void splitsTiersOnTheContextLengthCondition() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "len <= 272000 ? tier(\"standard\", p * 2.5 + c * 15 + cr * 0.25)"
                        + " : tier(\"long_context\", p * 5 + c * 22.5 + cr * 0.5)");

        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).minTokens()).isZero();
        assertThat(tiers.get(0).maxTokens()).isEqualTo(272_000L);
        assertThat(tiers.get(0).coefficients().input()).isEqualByComparingTo("2.5");
        assertThat(tiers.get(0).coefficients().output()).isEqualByComparingTo("15");
        assertThat(tiers.get(0).coefficients().cacheRead()).isEqualByComparingTo("0.25");

        assertThat(tiers.get(1).minTokens()).isEqualTo(272_001L);
        assertThat(tiers.get(1).maxTokens()).isNull();
        assertThat(tiers.get(1).coefficients().input()).isEqualByComparingTo("5");
        assertThat(tiers.get(1).coefficients().output()).isEqualByComparingTo("22.5");
        assertThat(tiers.get(1).coefficients().cacheRead()).isEqualByComparingTo("0.5");
    }

    @Test
    void treatsAStrictComparisonAsTheOtherSideOfTheThreshold() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "len < 1000 ? tier(\"short\", p * 1) : tier(\"long\", p * 2)");

        assertThat(tiers).hasSize(2);
        // 999 is still the short branch, 1000 already the long one.
        assertThat(tiers.get(0).maxTokens()).isEqualTo(999L);
        assertThat(tiers.get(1).minTokens()).isEqualTo(1000L);
        assertThat(tiers.get(1).coefficients().input()).isEqualByComparingTo("2");
    }

    @Test
    void readsCacheWriteAndImageTokenPrices() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "tier(\"standard\", p * 10 + c * 50 + cr * 1 + cc * 12.5 + img * 8 + img_o * 3)");

        BillingExprPrices.Coefficients coefficients = tiers.get(0).coefficients();
        assertThat(coefficients.input()).isEqualByComparingTo("10");
        assertThat(coefficients.output()).isEqualByComparingTo("50");
        assertThat(coefficients.cacheRead()).isEqualByComparingTo("1");
        assertThat(coefficients.cacheWrite()).isEqualByComparingTo("12.5");
        assertThat(coefficients.imageInput()).isEqualByComparingTo("8");
        assertThat(coefficients.imageOutput()).isEqualByComparingTo("3");
    }

    @Test
    void readsAFixedAmountAsAPerRequestPrice() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "tier(\"request\", fixed(0.00075))");

        BillingExprPrices.Coefficients coefficients = tiers.get(0).coefficients();
        assertThat(coefficients.perRequest()).isEqualByComparingTo("0.00075");
        assertThat(coefficients.input()).isNull();
        assertThat(coefficients.output()).isNull();
    }

    @Test
    void ignoresComponentsTheExpressionExplicitlyPricesAtZero() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "tier(\"standard\", p * 4 + c * 8 + cr * 0)");

        assertThat(tiers.get(0).coefficients().cacheRead()).isNull();
        assertThat(tiers.get(0).coefficients().input()).isEqualByComparingTo("4");
    }

    @Test
    void refusesTimeWindowPricingInsteadOfGuessing() {
        // DeepSeek peak / off-peak: a catalogue cannot know which window a call lands in.
        assertThat(BillingExprPrices.tiers(
                "weekday(\"Asia/Shanghai\") >= 1 && weekday(\"Asia/Shanghai\") <= 5"
                        + " ? tier(\"peak\", p * 0.3 + c * 1.2)"
                        + " : tier(\"off_peak\", p * 0.15 + c * 0.6)"))
                .isEmpty();
    }

    @Test
    void refusesRequestRulesAndUnknownVariables() {
        assertThat(BillingExprPrices.tiers(
                "tier(\"standard\", p * 2) * requestRule(0, true, 1.5)")).isEmpty();
        assertThat(BillingExprPrices.tiers("tier(\"standard\", mystery * 2)")).isEmpty();
    }

    @Test
    void refusesBlankOrMalformedExpressions() {
        assertThat(BillingExprPrices.tiers(null)).isEmpty();
        assertThat(BillingExprPrices.tiers("  ")).isEmpty();
        assertThat(BillingExprPrices.tiers("tier(\"standard\", p * )")).isEmpty();
        assertThat(BillingExprPrices.tiers("tier(\"standard\", p * 2) trailing")).isEmpty();
    }

    @Test
    void keepsWhitespaceAndPrecedenceOutOfTheResult() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "tier(\"standard\", (p * 2 + 1) * 3 + c * (10 - 4) / 2)");

        // (2p + 1) * 3 = 6p + 3, so the coefficient of p is 6 and the stray constant is not a charge.
        assertThat(tiers.get(0).coefficients().input()).isEqualByComparingTo("6");
        assertThat(tiers.get(0).coefficients().output()).isEqualByComparingTo("3");
        assertThat(tiers.get(0).coefficients().perRequest()).isNull();
    }

    @Test
    void returnsBigDecimalsWithoutBinaryNoise() {
        List<BillingExprPrices.Tier> tiers = BillingExprPrices.tiers(
                "tier(\"standard\", p * 0.1 + c * 0.2 + cr * 0.003)");

        BillingExprPrices.Coefficients coefficients = tiers.get(0).coefficients();
        assertThat(coefficients.input()).isEqualTo(new BigDecimal("0.1"));
        assertThat(coefficients.output()).isEqualTo(new BigDecimal("0.2"));
        assertThat(coefficients.cacheRead()).isEqualTo(new BigDecimal("0.003"));
    }
}
