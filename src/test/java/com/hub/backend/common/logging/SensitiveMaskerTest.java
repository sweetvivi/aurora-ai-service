package com.hub.backend.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住脱敏行为。这套规则一旦回归，后果是密码和 token 明文进数据库，
 * 比日志缺失严重得多，所以每条策略都单独断言。
 */
class SensitiveMaskerTest {

    @Test
    void deniesSensitiveFieldsWhateverTheirCase() {
        String masked = SensitiveMasker.maskJson(
                """
                {"username":"qa_probe","password":"Secret123!","accessToken":"abc.def"}
                """, 4096);

        // 用户名要留着（不然日志没用了），密码和 token 一个字都不能剩。
        assertTrue(masked.contains("\"username\":\"qa_probe\""));
        assertFalse(masked.contains("Secret123!"));
        assertFalse(masked.contains("abc.def"));
        assertTrue(masked.contains("\"password\":\"***\""));
    }

    @Test
    void masksPhoneNumberHiddenInAnOrdinaryField() {
        // 字段名是 content，看不出敏感；只有值形态能兜住这种。
        String masked = SensitiveMasker.maskJson("{\"content\":\"手机号 13812345678 请回电\"}", 4096);

        assertTrue(masked.contains("138****5678"));
        assertFalse(masked.contains("13812345678"));
    }

    @Test
    void keepsJwtPrefixForCorrelationButNotEnoughToReplay() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c";
        String masked = SensitiveMasker.maskJson("{\"note\":\"" + jwt + "\"}", 4096);

        // 前 8 位足够对上「是哪把 key」，但拼不出可用的令牌。
        assertTrue(masked.contains("eyJhbGci***"));
        assertFalse(masked.contains("eyJzdWIiOiIxMjM0In0"));
        assertFalse(masked.contains("sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c"));
    }

    @Test
    void masksEmailAndIdCard() {
        String masked = SensitiveMasker.maskJson(
                "{\"feedback\":\"联系人 alice@example.com 证件 110101199003074567\"}", 4096);

        assertFalse(masked.contains("alice@example.com"));
        assertFalse(masked.contains("110101199003074567"));
        assertTrue(masked.contains("a***@example.com"));
        assertTrue(masked.contains("110101********4567"));
    }

    @Test
    void walksNestedObjectsAndArrays() {
        String masked = SensitiveMasker.maskJson(
                """
                {"data":{"items":[{"token":"leak-me"},{"nickname":"bob"}]}}
                """, 4096);

        assertFalse(masked.contains("leak-me"));
        assertTrue(masked.contains("\"nickname\":\"bob\""));
    }

    @Test
    void fallsBackToRegexWhenTheBodyIsNotJson() {
        // 畸形 JSON 不能让整条日志丢掉：脱敏失败就退化成文本兜底。
        String masked = SensitiveMasker.maskJson("password=Secret123!&phone=13812345678", 4096);

        assertFalse(masked.contains("13812345678"));
        assertTrue(masked.contains("138****5678"));
    }

    @Test
    void masksSensitiveQueryParametersByKey() {
        String masked = SensitiveMasker.maskQuery("page=1&password=Secret123!&phone=13812345678", 1024);

        assertTrue(masked.contains("page=1"));
        assertTrue(masked.contains("password=***"));
        assertFalse(masked.contains("Secret123!"));
        assertFalse(masked.contains("13812345678"));
    }

    @Test
    void stopsDescendingPastTheDepthLimit() {
        String deep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":\"bottom\"}}}}}}}}}";
        String masked = SensitiveMasker.maskJson(deep, 4096);

        assertTrue(masked.contains("***max-depth***"));
        assertFalse(masked.contains("bottom"));
    }

    @Test
    void truncatesOversizedBodies() {
        String body = "{\"content\":\"" + "x".repeat(500) + "\"}";
        String masked = SensitiveMasker.maskJson(body, 100);

        assertTrue(masked.endsWith("…(truncated)"));
        assertTrue(masked.length() <= 100 + "…(truncated)".length());
    }

    @Test
    void treatsFieldNamesCaseAndSeparatorInsensitively() {
        // access_token / AccessToken / accessToken 都要命中同一个黑名单项。
        assertTrue(SensitiveMasker.isDeniedField("access_token"));
        assertTrue(SensitiveMasker.isDeniedField("AccessToken"));
        assertTrue(SensitiveMasker.isDeniedField("access-token"));
        assertFalse(SensitiveMasker.isDeniedField("username"));
    }

    @Test
    void masksEveryPasswordVariantNotJustOldNewConfirm() {
        // 回归防护：改密码接口用的是 currentPassword，黑名单漏了它，
        // 用户的当前密码就会以明文进 request_logs（真实发生过）。
        String masked = SensitiveMasker.maskJson(
                """
                {"currentPassword":"LogTest!2026","newPassword":"LogTest!2026x"}
                """, 4096);

        assertFalse(masked.contains("LogTest!2026"));
        assertTrue(masked.contains("\"currentPassword\":\"***\""));
        assertTrue(masked.contains("\"newPassword\":\"***\""));
    }

    @Test
    void masksRedemptionCodeBecauseItIsAsGoodAsCash() {
        // 兑换码等同现金：读日志的人拿它可以替用户把额度领走。
        String masked = SensitiveMasker.maskJson(
                "{\"code\":\"AMT6M0DjYJNbB6sRXhlYWhF78wYmjDji\",\"password\":\"***\"}", 4096);

        assertFalse(masked.contains("AMT6M0DjYJNbB6sRXhlYWhF78wYmjDji"));
        assertTrue(masked.contains("\"code\":\"***\""));
    }

    @Test
    void keepsNonSecretCodeFieldsReadable() {
        // code 只精确匹配字段名：errorCode / modelCode 这类排查信息必须留着。
        assertTrue(SensitiveMasker.isDeniedField("code"));
        assertFalse(SensitiveMasker.isDeniedField("errorCode"));
        assertFalse(SensitiveMasker.isDeniedField("model_code"));
        assertFalse(SensitiveMasker.isDeniedField("statusCode"));
    }

    @Test
    void masksCodeInQueryStringToo() {
        String masked = SensitiveMasker.maskQuery("code=AMT6M0Dj&page=1", 1024);

        assertFalse(masked.contains("AMT6M0Dj"));
        assertTrue(masked.contains("code=***"));
        assertTrue(masked.contains("page=1"));
    }
}
