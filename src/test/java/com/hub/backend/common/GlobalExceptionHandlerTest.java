package com.hub.backend.common;

import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.exception.UpstreamFailureText;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void dataIntegrityViolationsReturnSafeConflictResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/admin/v1/agents");

        var response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("constraint user_accounts_fk leaked detail"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("数据与现有记录冲突，请检查关联项或唯一字段")
                .doesNotContain("user_accounts_fk");
    }

    /**
     * 上游 400「这张图违反内容政策」以前一律折叠成 502：前端据此判断"接口没实现"并降级重试，
     * 用户只看到 Bad Gateway。这组用例把新的映射钉住。
     */
    private static final String POLICY_BODY =
            "{\"error\":{\"message\":\"We are so sorry, but the image we created may violate our "
                    + "content policies.\",\"type\":\"invalid_request_error\",\"param\":\"\","
                    + "\"code\":\"upstream_text_reply\"}}";

    private final MockHttpServletRequest canvasRequest = new MockHttpServletRequest(
            "POST", "/api/v1/ai-canvas/images/generations");

    @Test
    void contentPolicyRefusalReturnsClientErrorWithReadableReason() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Image editing gateway returned HTTP 400: " + POLICY_BODY,
                        400,
                        "upstream_text_reply"),
                canvasRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo(UpstreamFailureText.CONTENT_POLICY_TEXT);
        assertThat(response.getBody().details()).containsEntry("upstreamCode", "upstream_text_reply");
    }

    @Test
    void missingUpstreamStatusStaysBadGateway() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleLlmFailure(
                new LlmInvocationException("Model gateway returned HTTP 502: bad gateway"),
                canvasRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        // HTTP 状态在 status 字段；message 只给短中文，不透出 502 / 英文原文。
        assertThat(response.getBody().message()).isEqualTo(UpstreamFailureText.GENERIC_TEXT);
    }

    @Test
    void upstreamServerErrorStaysBadGateway() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleLlmFailure(
                new LlmInvocationException("Model gateway returned HTTP 503: busy", 503, null),
                canvasRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void upstreamCredentialFailuresAreNotPassedThrough() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // 401/403 描述的是「我们与上游之间」的问题；透传 401 会让前端把用户踢去登录。
        assertThat(handler.handleLlmFailure(
                new LlmInvocationException("Model gateway returned HTTP 401: invalid api key", 401, null),
                canvasRequest).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(handler.handleLlmFailure(
                new LlmInvocationException("Model gateway returned HTTP 403: no access", 403, null),
                canvasRequest).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void rateLimitIsPassedThrough() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleLlmFailure(
                new LlmInvocationException("Model gateway returned HTTP 429: too many requests", 429, null),
                canvasRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void accessTokenFailuresCollapseToLoginExpired() {
        assertThat(GlobalExceptionHandler.userFacingUnauthorized("Access token has expired"))
                .isEqualTo(ApiMessages.LOGIN_EXPIRED);
        assertThat(GlobalExceptionHandler.userFacingUnauthorized("Incorrect username or password"))
                .isEqualTo(ApiMessages.INVALID_CREDENTIALS);
        assertThat(GlobalExceptionHandler.userFacingUnauthorized(ApiMessages.LOGIN_REQUIRED))
                .isEqualTo(ApiMessages.LOGIN_REQUIRED);
    }

    @Test
    void englishNotFoundBecomesGenericChinese() {
        assertThat(GlobalExceptionHandler.userFacingNotFound("Agent 'x' was not found"))
                .isEqualTo(ApiMessages.NOT_FOUND);
        assertThat(GlobalExceptionHandler.userFacingNotFound("该稿件还没有生成全文"))
                .isEqualTo("该稿件还没有生成全文");
    }

    @Test
    void serviceUnavailableSanitizesEnglishOpsMessages() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/billing");

        var response = handler.handleServiceUnavailable(
                new ServiceUnavailableException("New API integration is disabled"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ApiMessages.SERVICE_UNAVAILABLE);
    }

    @Test
    void unauthorizedHandlerUsesSanitizedMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");

        var response = handler.handleUnauthorized(
                new UnauthorizedException("Access token is malformed"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ApiMessages.LOGIN_EXPIRED);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.AUTH_EXPIRED);
    }

    @Test
    void badCredentialsUseInvalidInputCodeNotAuthExpired() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

        var response = handler.handleUnauthorized(
                new UnauthorizedException("Incorrect username or password"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ApiMessages.INVALID_CREDENTIALS);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void notFoundHandlerKeepsChineseBusinessCopy() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/thesis/1");

        var response = handler.handleBusinessNotFound(
                new NotFoundException("该稿件还没有生成全文，打开大纲后点「生成全文」即可"),
                request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("生成全文");
    }

    @Test
    void adminInvalidRequestKeepsEnglishValidationDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/admin/v1/models/12");

        var response = handler.handleInvalidRequest(
                new InvalidRequestException("aspectRatio must look like 16:9, got 'widescreen'"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("aspectRatio must look like 16:9, got 'widescreen'");
    }

    @Test
    void publicInvalidRequestCollapsesEnglishToGenericChinese() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/videos/generations");

        var response = handler.handleInvalidRequest(
                new InvalidRequestException("aspectRatio must look like 16:9, got 'widescreen'"),
                request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ApiMessages.BAD_REQUEST);
    }

    @Test
    void publicConflictKeepsChineseBusinessCopy() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api/v1/videos/tasks/vt_1");

        var response = handler.handleConflict(
                new ConflictException(ApiMessages.VIDEO_TASK_STILL_RUNNING),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ApiMessages.VIDEO_TASK_STILL_RUNNING);
    }

    @Test
    void isAdminApiRecognisesAdminPrefixAndContextPath() {
        MockHttpServletRequest admin = new MockHttpServletRequest("GET", "/api/admin/v1/models");
        assertThat(GlobalExceptionHandler.isAdminApi(admin)).isTrue();

        MockHttpServletRequest withContext = new MockHttpServletRequest(
                "GET", "/backend/api/admin/v1/models");
        withContext.setContextPath("/backend");
        assertThat(GlobalExceptionHandler.isAdminApi(withContext)).isTrue();

        MockHttpServletRequest publicApi = new MockHttpServletRequest(
                "GET", "/api/v1/models");
        assertThat(GlobalExceptionHandler.isAdminApi(publicApi)).isFalse();
    }

    /* ------------------------------------------------------------------ */
    /* 业务码：前端靠它归类，不再拿关键词猜                                */
    /* ------------------------------------------------------------------ */

    @Test
    void contentPolicyRefusalCarriesContentBlockedCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Image editing gateway returned HTTP 400: " + POLICY_BODY,
                        400,
                        "upstream_text_reply"),
                canvasRequest);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CONTENT_BLOCKED);
    }

    @Test
    void upstreamFailuresCarrySemanticCodes() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var quota = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Model gateway returned HTTP 403: "
                                + "{\"error\":{\"message\":\"预扣费额度失败\","
                                + "\"code\":\"insufficient_user_quota\"}}",
                        403,
                        "insufficient_user_quota"),
                canvasRequest);
        assertThat(quota.getBody()).isNotNull();
        assertThat(quota.getBody().code()).isEqualTo(ErrorCode.INSUFFICIENT_QUOTA);

        var model = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Model gateway returned HTTP 400: {\"error\":{\"message\":"
                                + "\"Model is not available for this group\","
                                + "\"code\":\"model_not_found\"}}",
                        400,
                        "model_not_found"),
                canvasRequest);
        assertThat(model.getBody()).isNotNull();
        assertThat(model.getBody().code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);

        // 站点插件没给该模型配 channel 映射：同样属于「换模型」而不是「稍后重试」。
        var plugin = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Model gateway returned HTTP 400: {\"error\":{\"message\":"
                                + "\"plugin newbanana@1.1.0 hook buildSubmitRequest failed: "
                                + "Error: configure channel model_mapping for "
                                + "gemini-3-pro-image-preview\","
                                + "\"code\":\"plugin_request_invalid\"}}",
                        400,
                        "plugin_request_invalid"),
                canvasRequest);
        assertThat(plugin.getBody()).isNotNull();
        assertThat(plugin.getBody().code()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE);

        // 上游把可用参数写在报文里：照抄给用户，重试原参数永远不会成功。
        var resolution = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Video gateway returned HTTP 400: {\"error\":{\"code\":\"\",\"message\":"
                                + "\"resolution must be 720p for seedance-2.5-omni\"}}",
                        400,
                        ""),
                canvasRequest);
        assertThat(resolution.getBody()).isNotNull();
        assertThat(resolution.getBody().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(resolution.getBody().message()).contains("720p");
    }

    /**
     * 这是整件事的落点：上游报文里带 safety / blocked 这类泛词时，业务码说了算。
     * 以前这条会返回「内容未通过审核」，把用户推去改一个本来没问题的提示词。
     */
    @Test
    void bareSafetyWordsNoLongerBecomeContentBlocked() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleLlmFailure(
                new LlmInvocationException(
                        "Model gateway returned HTTP 502: the request was blocked by our safety team",
                        502,
                        null),
                canvasRequest);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.UPSTREAM_FAILED);
        assertThat(response.getBody().message()).doesNotContain("审核");
    }

    @Test
    void statusDerivedCodesCoverTheCommonFamilies() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var unauthorized = handler.handleUnauthorized(
                new UnauthorizedException("Access token has expired"),
                new MockHttpServletRequest("GET", "/api/v1/me"));
        assertThat(unauthorized.getBody()).isNotNull();
        assertThat(unauthorized.getBody().code()).isEqualTo(ErrorCode.AUTH_EXPIRED);

        var forbidden = handler.handleForbidden(
                new com.hub.backend.common.exception.ForbiddenException("not your resource"),
                new MockHttpServletRequest("GET", "/api/v1/me"));
        assertThat(forbidden.getBody()).isNotNull();
        assertThat(forbidden.getBody().code()).isEqualTo(ErrorCode.FORBIDDEN);

        var payment = handler.handlePaymentRequired(
                new com.hub.backend.common.exception.PaymentRequiredException("Insufficient balance"),
                new MockHttpServletRequest("POST", "/api/v1/images/generations"));
        assertThat(payment.getBody()).isNotNull();
        assertThat(payment.getBody().code()).isEqualTo(ErrorCode.INSUFFICIENT_QUOTA);

        var rateLimited = handler.handleTooManyRequests(
                new com.hub.backend.common.exception.TooManyRequestsException("slow down", 30),
                new MockHttpServletRequest("POST", "/api/v1/images/generations"));
        assertThat(rateLimited.getBody()).isNotNull();
        assertThat(rateLimited.getBody().code()).isEqualTo(ErrorCode.RATE_LIMITED);
    }
}
