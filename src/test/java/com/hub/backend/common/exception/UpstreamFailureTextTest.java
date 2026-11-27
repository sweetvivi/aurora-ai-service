package com.hub.backend.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游报文的可读化：这是用户唯一能看到的那句话，回归成本高于实现成本。
 */
class UpstreamFailureTextTest {

    private static final String POLICY_SENTENCE =
            "非常抱歉，该提示可能违反了关于裸露、色情或情色内容的防护限制。如果你认为此判断有误，请重试或修改提示语。";

    private static final String CONTENT_POLICY_400 =
            "Image editing gateway returned HTTP 400: {\"error\":{\"message\":\"" + POLICY_SENTENCE + "\","
                    + "\"type\":\"invalid_request_error\",\"param\":\"\",\"code\":\"upstream_text_reply\"}}";

    private static final String CONTENT_POLICY_400_EN =
            "Image editing gateway returned HTTP 400: {\"error\":{\"message\":\"We’re so sorry, but "
                    + "the image we created may violate our content policies. If you think we got it "
                    + "wrong, please retry or edit your prompt.\",\"type\":\"invalid_request_error\","
                    + "\"param\":\"\",\"code\":\"upstream_text_reply\"}}";

    /**
     * 同一个 code 的非审核用法：上游没出图、只回了一句文本。
     * 这是线上真实踩到的形态——提示词完全正常，用户却被引导去「更换参考图」。
     */
    private static final String TEXT_REPLY_400 =
            "Model gateway returned HTTP 400: {\"error\":{\"message\":\"The model returned a text "
                    + "response instead of an image.\",\"type\":\"invalid_request_error\","
                    + "\"param\":\"\",\"code\":\"upstream_text_reply\"}}";

    private static final String QUOTA_403 =
            "Model gateway returned HTTP 403: {\"error\":{\"message\":\"预扣费额度失败，"
                    + "用户剩余额度：$0.240750，需预扣费额度：$0.402900\","
                    + "\"type\":\"new_api_error\",\"code\":\"insufficient_user_quota\"}}";

    @Test
    @DisplayName("把 error.message 从嵌套 JSON 里提出来，不带上游英文包壳")
    void extractsNestedErrorMessage() {
        assertEquals(POLICY_SENTENCE, UpstreamFailureText.readable(CONTENT_POLICY_400, "兜底"));
    }

    @Test
    @DisplayName("顶层 message 也认")
    void extractsTopLevelMessage() {
        String raw = "Model gateway returned HTTP 429: {\"message\":\"配额不足\"}";
        assertEquals("配额不足", UpstreamFailureText.readable(raw, "兜底"));
    }

    @Test
    @DisplayName("不是 JSON（被截断）就原样回落，不编造原因")
    void fallsBackToRawWhenNotJson() {
        String truncated = "Model gateway returned HTTP 502: <html>Bad Gateway";
        assertEquals(truncated, UpstreamFailureText.readable(truncated, "兜底"));
    }

    @Test
    @DisplayName("空消息用调用方给的兜底文案")
    void usesFallbackForBlank() {
        assertEquals("图片生成失败", UpstreamFailureText.readable(null, "图片生成失败"));
        assertEquals("图片生成失败", UpstreamFailureText.readable("   ", "图片生成失败"));
    }

    @Test
    @DisplayName("过长时截断，并留省略号表明还有下文")
    void truncatesLongText() {
        String longSentence = "很".repeat(400);
        String raw = "gateway returned HTTP 400: {\"error\":{\"message\":\"" + longSentence + "\"}}";
        String readable = UpstreamFailureText.readable(raw, "兜底");
        assertTrue(readable.length() <= 201, "截断后不应超过 200 字加省略号");
        assertTrue(readable.endsWith("…"));
    }

    @Test
    @DisplayName("认 upstream_text_reply：中英文报文都换成同一句中文")
    void describeMapsContentPolicyCodeRegardlessOfLanguage() {
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describe(CONTENT_POLICY_400, "upstream_text_reply", "兜底"));
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describe(CONTENT_POLICY_400_EN, "upstream_text_reply", "兜底"));
    }

    @Test
    @DisplayName("无 code 时按审核关键词也能归到统一中文")
    void describeMapsModerationByKeywords() {
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describe(CONTENT_POLICY_400, null, "兜底"));
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describe(CONTENT_POLICY_400_EN, null, "兜底"));
    }

    @Test
    @DisplayName("upstream_text_reply 不带审核语义时，不能说成内容违规")
    void describeKeepsTextReplySeparateFromModeration() {
        String text = UpstreamFailureText.describe(TEXT_REPLY_400, "upstream_text_reply", "兜底");

        assertEquals(UpstreamFailureText.TEXT_REPLY_TEXT, text);
        assertTrue(!text.contains("审核"), "没出图和内容违规是两件事，不能混为一谈");
        assertTrue(!text.contains("参考图"), "对话链路也会命中这个 code，不允许出现生图场景词");
    }

    @Test
    @DisplayName("报文里只带 code、没有审核词时同样不判审核")
    void describeDoesNotTreatBareTextReplyCodeAsModeration() {
        assertEquals(UpstreamFailureText.TEXT_REPLY_TEXT,
                UpstreamFailureText.describe("upstream_text_reply", null, "兜底"));
    }

    @Test
    @DisplayName("model_not_found / 未定价 → 统一模型不可用短句")
    void describeExplainsModelUnavailable() {
        String raw = "Image editing gateway returned HTTP 400: "
                + "{\"error\":{\"message\":\"Model \\\"gpt-image-2\\\" is not available for this group\","
                + "\"code\":\"model_not_found\"}}";

        assertEquals(UpstreamFailureText.MODEL_UNAVAILABLE_TEXT,
                UpstreamFailureText.describe(raw, "model_not_found", "兜底"));
        assertEquals(UpstreamFailureText.MODEL_UNAVAILABLE_TEXT,
                UpstreamFailureText.describe(
                        "Model gpt-image-2 has not been priced by the administrator", null, "兜底"));
    }

    @Test
    @DisplayName("upstreamCode 能从报文里读出来")
    void parsesUpstreamCode() {
        assertEquals("upstream_text_reply", UpstreamFailureText.upstreamCode(CONTENT_POLICY_400_EN));
        assertEquals("upstream_text_reply", UpstreamFailureText.upstreamCode(CONTENT_POLICY_400));
        assertEquals(null, UpstreamFailureText.upstreamCode("Model gateway returned HTTP 502: bad gateway"));
        assertEquals(null, UpstreamFailureText.upstreamCode(null));
    }

    @Test
    @DisplayName("余额不足：全站统一短句，不带金额与「模型账户」长说明")
    void describeUsesShortQuotaText() {
        assertEquals(UpstreamFailureText.INSUFFICIENT_BALANCE_TEXT,
                UpstreamFailureText.describe(QUOTA_403, "insufficient_user_quota", "兜底"));

        String quota402 = "Model gateway returned HTTP 402: {\"error\":{\"message\":\"余额不足\"}}";
        assertEquals(UpstreamFailureText.INSUFFICIENT_BALANCE_TEXT,
                UpstreamFailureText.describe(quota402, null, "兜底"));
    }

    @Test
    @DisplayName("视频常见英文失败 → 短中文")
    void describeAsyncMapsVideoFailures() {
        assertEquals(UpstreamFailureText.RESULT_MISSING_TEXT,
                UpstreamFailureText.describeAsync("Generated video download returned HTTP 404"));
        assertEquals(UpstreamFailureText.BUSY_TEXT,
                UpstreamFailureText.describeAsync(
                        "Generated video URL points at a non-public address: 127.0.0.1"));
        assertEquals(UpstreamFailureText.LOGIN_SYNC_TEXT,
                UpstreamFailureText.describeAsync(
                        "The New API password of this account is not synchronised yet; sign in again"));
        assertEquals(UpstreamFailureText.MODEL_UNAVAILABLE_TEXT,
                UpstreamFailureText.describeAsync(
                        "Video generation request returned HTTP 422: "
                                + "{\"error\":{\"message\":\"xAI upstream returned status 422\","
                                + "\"type\":\"upstream_error\"}}"));
        assertEquals(UpstreamFailureText.GENERIC_TEXT,
                UpstreamFailureText.describeAsync("stale before status-path fix"));
    }

    @Test
    @DisplayName("网关连不上 → 系统繁忙，而不是「发生错误」")
    void describeAsyncMapsUnreachableGateway() {
        // NewApiManagementClient 把 IOException 包成 ServiceUnavailableException 后的原始文案，
        // VideoGenerationService 在这里把它落成 async task 的 errorMessage。
        assertEquals(UpstreamFailureText.BUSY_TEXT,
                UpstreamFailureText.describeAsync(
                        "The video request could not be submitted: New API is unavailable"));
        assertEquals(UpstreamFailureText.BUSY_TEXT,
                UpstreamFailureText.describeAsync("java.net.ConnectException: Connection refused"));
        // 不能把「模型不可用」也吞成系统繁忙：两条排查路径完全不同。
        assertEquals(UpstreamFailureText.MODEL_UNAVAILABLE_TEXT,
                UpstreamFailureText.describeAsync("The model is unavailable for this group"));
    }

    @Test
    @DisplayName("视频中转站审核英文 / fail_reason → 统一审核中文")
    void describeAsyncMapsVideoModerationFailures() {
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describeAsync(
                        "The prompt violates the content policy of the video model"));
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describeAsync(
                        "Input image flagged by safety moderation system"));
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describeAsync(
                        "Video task failed: {\"status\":\"failed\",\"fail_reason\":"
                                + "\"Request rejected due to sensitive content\"}"));
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describeAsync(
                        "Generation blocked: risk_control triggered for prohibited material"));
        assertEquals(UpstreamFailureText.CONTENT_POLICY_TEXT,
                UpstreamFailureText.describeAsync("提示词未通过审核，请修改后重试"));
    }

    @Test
    @DisplayName("裸词不能误判成审核：blocked / rejected / violat 单独出现都不是违规")
    void describeDoesNotTreatBareWordsAsModeration() {
        // account blocked 是账号封禁，不是内容违规
        assertEquals(UpstreamFailureText.GENERIC_TEXT,
                UpstreamFailureText.describeAsync("Your account has been blocked"));
        // request rejected 是参数/网关拒绝，不是内容违规
        assertEquals(UpstreamFailureText.GENERIC_TEXT,
                UpstreamFailureText.describeAsync("The request was rejected by the gateway"));
        // 裸 violat 后面不跟 content/safety/usage/community policy 就不算审核
        assertEquals(UpstreamFailureText.GENERIC_TEXT,
                UpstreamFailureText.describeAsync("The model violated the response contract"));
        // 裸 safety 后面不跟 filter/system/check 也不算审核
        assertEquals(UpstreamFailureText.GENERIC_TEXT,
                UpstreamFailureText.describeAsync("The prompt triggered a safety reminder"));
    }

    @Test
    @DisplayName("供应商算力过载 → 一句可操作的中文，不把 system_cpu_overloaded 透给用户")
    void describeExplainsCapacityOverload() {
        String raw = "Image editing gateway returned HTTP 400: "
                + "{\"error\":{\"message\":\"发生错误，请稍后重试：system_cpu_overloaded\","
                + "\"code\":\"system_cpu_overloaded\"}}";

        assertEquals(UpstreamFailureText.CAPACITY_TEXT,
                UpstreamFailureText.describe(raw, "system_cpu_overloaded", "兜底"));
        // 没有 code 时靠报文里的关键词同样能认出来
        assertEquals(UpstreamFailureText.CAPACITY_TEXT, UpstreamFailureText.describeAsync(raw));
        assertEquals(UpstreamFailureText.Kind.BUSY,
                UpstreamFailureText.describeWithKind(raw, null, "兜底").kind());
    }

    @Test
    @DisplayName("过载判定不影响原样透传后端自己写的中文业务句")
    void describeKeepsBusinessCjkThrough() {
        String business = "该稿件还没有生成全文，请先生成全文";
        assertEquals(business, UpstreamFailureText.describe(business, null, "兜底"));
    }
}
