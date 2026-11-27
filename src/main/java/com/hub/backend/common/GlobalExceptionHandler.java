package com.hub.backend.common;

import com.hub.backend.common.exception.ApiMessages;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.ForbiddenException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.common.exception.PasswordConfirmationRequiredException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import com.hub.backend.common.exception.StorageException;
import com.hub.backend.common.exception.TooManyRequestsException;
import com.hub.backend.common.exception.UnauthorizedException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.common.logging.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 上游什么都没说时的兜底文案；与 {@link UpstreamFailureText#GENERIC_TEXT} 对齐。 */
    private static final String UPSTREAM_UNAVAILABLE_TEXT = UpstreamFailureText.GENERIC_TEXT;

    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fa5]");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> details = bindingDetails(exception.getBindingResult());
        // 对外只有一句「请求参数有误」，字段细节必须留在日志里。少了这条，线上出现
        // "某个字段超长" 之类的问题，服务端完全无痕，只能靠猜（对话接口的 title 就这么栽过）。
        log.warn("Request validation failed on {}: {}", request.getRequestURI(), details);
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                ApiMessages.BAD_REQUEST,
                request,
                details);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBinding(
            BindException exception,
            HttpServletRequest request) {
        Map<String, String> details = bindingDetails(exception.getBindingResult());
        log.warn("Request binding failed on {}: {}", request.getRequestURI(), details);
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                ApiMessages.BAD_REQUEST,
                request,
                details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                ApiMessages.BODY_UNREADABLE,
                request,
                Map.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(
            MissingServletRequestPartException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                ApiMessages.BAD_REQUEST,
                request,
                Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                ApiMessages.BAD_REQUEST,
                request,
                Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ApiMessages.BAD_REQUEST,
                request,
                Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                "不支持该请求方法",
                request,
                Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.NOT_FOUND,
                ApiMessages.NOT_FOUND,
                request,
                Map.of());
    }

    private static Map<String, String> bindingDetails(
            org.springframework.validation.BindingResult bindingResult) {
        Map<String, String> details = new LinkedHashMap<>();
        bindingResult.getFieldErrors()
                .forEach(error -> details.put(error.getField(), error.getDefaultMessage()));
        return details;
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ProductNotFoundException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.NOT_FOUND, userFacingNotFound(exception.getMessage()), request, Map.of());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleBusinessNotFound(
            NotFoundException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.NOT_FOUND,
                adminOrUserFacing(request, exception.getMessage(), ApiMessages.NOT_FOUND,
                        GlobalExceptionHandler::userFacingNotFound),
                request,
                Map.of());
    }

    @ExceptionHandler(LlmInvocationException.class)
    public ResponseEntity<ApiError> handleLlmFailure(
            LlmInvocationException exception,
            HttpServletRequest request) {
        // 原文只进日志：出图 / 画布 / 文档这些链路原本没有任何地方记录上游报文，
        // 一旦上游回 400 又没给出可读原因，日志里就只剩「发生错误，请稍后重试」，无从排查。
        log.warn("Upstream failure on {} (status={}, code={}): {}",
                request.getRequestURI(),
                exception.getUpstreamStatus(),
                exception.getUpstreamCode(),
                exception.getMessage());
        UpstreamFailureText.Description described = UpstreamFailureText.describeWithKind(
                exception.getMessage(), exception.getUpstreamCode(), UPSTREAM_UNAVAILABLE_TEXT);
        Map<String, String> details = exception.getUpstreamCode() == null
                ? Map.of()
                : Map.of("upstreamCode", exception.getUpstreamCode());
        return errorResponse(
                upstreamFailureStatus(exception.getUpstreamStatus()),
                described.message(),
                request,
                details,
                codeOf(described.kind()));
    }

    /** 上游归因 → 对外业务码。 */
    static String codeOf(UpstreamFailureText.Kind kind) {
        return switch (kind) {
            case CONTENT_BLOCKED -> ErrorCode.CONTENT_BLOCKED;
            case INSUFFICIENT_QUOTA -> ErrorCode.INSUFFICIENT_QUOTA;
            case MODEL_UNAVAILABLE -> ErrorCode.MODEL_UNAVAILABLE;
            // 「上游只回了文字」不是审核，也不是可命名的原因：归通用上游失败。
            case TEXT_REPLY -> ErrorCode.UPSTREAM_FAILED;
            case LOGIN_SYNC_REQUIRED -> ErrorCode.LOGIN_SYNC_REQUIRED;
            case AUTH_EXPIRED -> ErrorCode.AUTH_EXPIRED;
            case TIMEOUT -> ErrorCode.TIMEOUT;
            case RATE_LIMITED -> ErrorCode.RATE_LIMITED;
            case RESULT_MISSING -> ErrorCode.RESULT_MISSING;
            case BUSY -> ErrorCode.BUSY;
            case INVALID_REQUEST -> ErrorCode.INVALID_INPUT;
            case UPSTREAM_FAILED -> ErrorCode.UPSTREAM_FAILED;
        };
    }

    /**
     * 上游明确判定「这次请求本身有问题」时，把状态码原样还给客户端；其余一律 502。
     *
     * <p>以前这里是硬编码的 {@code BAD_GATEWAY}：上游一句 400「这张图违反内容政策」，到用户面前
     * 变成「Bad Gateway」，前端还会把它当成"接口没实现"去降级重试。问题不在网关，在映射。</p>
     *
     * <p>只放行这四个，因为它们对用户是有意义、且前端不会误处理的：</p>
     * <ul>
     *   <li>{@code 400} / {@code 422} — 内容审核拒绝、参数不被模型接受，改提示词就能重试</li>
     *   <li>{@code 413} — 图太大，换一张</li>
     *   <li>{@code 429} — 上游限流，等一会儿再来（前端有 rate-limit 文案映射）</li>
     * </ul>
     *
     * <p><b>401 / 403 / 404 故意不放行</b>：它们描述的是<em>我们</em>和上游之间的问题（key 失效、
     * 无权限、路径写错），不是用户的。透传 401 会让前端把用户踢去登录，透传 404 会被当成资源
     * 不存在——两种都会把运维问题伪装成用户问题。</p>
     */
    private static HttpStatus upstreamFailureStatus(Integer upstreamStatus) {
        if (upstreamStatus == null) {
            return HttpStatus.BAD_GATEWAY;
        }
        HttpStatus resolved = switch (upstreamStatus) {
            case 400, 413, 422, 429 -> HttpStatus.resolve(upstreamStatus);
            default -> null;
        };
        return resolved == null ? HttpStatus.BAD_GATEWAY : resolved;
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(
            InvalidRequestException exception,
            HttpServletRequest request) {
        // C 端看到的可能是被归一后的短句，原因只在日志里，所以这里必须留痕
        log.warn("Invalid request on {}: {}", request.getRequestURI(), exception.getMessage());
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                adminOrUserFacing(request, exception.getMessage(), ApiMessages.BAD_REQUEST,
                        GlobalExceptionHandler::userFacingClientMessage),
                request,
                Map.of());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(
            UnauthorizedException exception,
            HttpServletRequest request) {
        String message = userFacingUnauthorized(exception.getMessage());
        // 登录页「账号密码错」与「会话失效」不能共用 AUTH_EXPIRED：前端码优先后，
        // 会把还没登进去的人说成「登录已失效」。凭证类错误归 INVALID_INPUT。
        String code = isInvalidCredentialsFacing(message)
                ? ErrorCode.INVALID_INPUT
                : ErrorCode.AUTH_EXPIRED;
        return errorResponse(HttpStatus.UNAUTHORIZED, message, request, Map.of(), code);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(
            ForbiddenException exception,
            HttpServletRequest request) {
        // 管理端需要看到「哪个字段不合法」一类原文；C 端仍归一成短中文。
        return errorResponse(
                HttpStatus.FORBIDDEN,
                adminOrUserFacing(request, exception.getMessage(), ApiMessages.FORBIDDEN,
                        GlobalExceptionHandler::userFacingForbidden),
                request,
                Map.of());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(
            ConflictException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.CONFLICT,
                adminOrUserFacing(request, exception.getMessage(), ApiMessages.CONFLICT,
                        GlobalExceptionHandler::userFacingConflict),
                request,
                Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Database constraint conflict on {}", request.getRequestURI(), exception);
        return errorResponse(
                HttpStatus.CONFLICT,
                "数据与现有记录冲突，请检查关联项或唯一字段",
                request,
                Map.of());
    }

    @ExceptionHandler(PaymentRequiredException.class)
    public ResponseEntity<ApiError> handlePaymentRequired(
            PaymentRequiredException exception,
            HttpServletRequest request) {
        // 全站统一短句：不再把英文 "Insufficient account balance…" 透给前端。
        String message = UpstreamFailureText.describe(
                exception.getMessage(), null, UpstreamFailureText.INSUFFICIENT_BALANCE_TEXT);
        return errorResponse(HttpStatus.PAYMENT_REQUIRED, message, request, Map.of());
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiError> handlePayloadTooLarge(
            PayloadTooLargeException exception,
            HttpServletRequest request) {
        // 413 有两种语义：文件本身太大，和账号存储空间已满。后者是业务代码主动写的
        // 中文提示（「存储空间不足（已用 X MB，上限 Y MB）」），归一成「附件超过允许的大小」
        // 会把人带到错误的方向 —— 用户根本没在上传附件，他要做的是删掉旧文件。
        return errorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                keepCjkOr(exception.getMessage(), ApiMessages.UPLOAD_TOO_LARGE),
                request,
                Map.of());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleServiceUnavailable(
            ServiceUnavailableException exception,
            HttpServletRequest request) {
        // 原文进日志；响应用短中文（已是业务中文的保留，英文/路径类归一）。
        log.warn("Service unavailable on {}: {}", request.getRequestURI(), exception.getMessage());
        return errorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                userFacingServiceUnavailable(exception.getMessage()),
                request,
                Map.of());
    }

    /**
     * The operation needs the account password this server session no longer holds.
     *
     * <p>428 rather than 401 or 503 on purpose: the caller's token is still valid, and the request
     * can succeed unchanged once the password is supplied — a client shows a password prompt and
     * retries. 401 would sign the user out, 503 would invite pointless retries.</p>
     */
    @ExceptionHandler(PasswordConfirmationRequiredException.class)
    public ResponseEntity<ApiError> handlePasswordConfirmationRequired(
            PasswordConfirmationRequiredException exception,
            HttpServletRequest request) {
        return errorResponse(
                HttpStatus.PRECONDITION_REQUIRED,
                ApiMessages.PASSWORD_CONFIRM_REQUIRED,
                request,
                Map.of());
    }

    /**
     * Raised by the servlet container before the request ever reaches a controller, when the body
     * is larger than {@code spring.servlet.multipart.max-file-size}.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleContainerUploadLimit(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.PAYLOAD_TOO_LARGE, ApiMessages.UPLOAD_TOO_LARGE, request, Map.of());
    }

    /**
     * Storage failures are internal: the detail is logged, the client only gets a stable message so
     * that no server path or credential can leak through the response.
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiError> handleStorageFailure(
            StorageException exception,
            HttpServletRequest request) {
        log.error("Attachment storage failure on {}", request.getRequestURI(), exception);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                ApiMessages.ATTACHMENT_FAILED, request, Map.of());
    }

    /**
     * Rate limited requests answer with {@code Retry-After} so a well behaved client can back off
     * without guessing.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(
            TooManyRequestsException exception,
            HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                // 同理保留业务中文：「出图太频繁（24 张/分钟）」比「请求过于频繁」有用得多，
                // 后者让用户不知道该等多久、也不知道限的是什么。
                keepCjkOr(exception.getMessage(), UpstreamFailureText.RATE_LIMIT_TEXT),
                request.getRequestURI(),
                Map.of(),
                ErrorCode.RATE_LIMITED);
        // 限流是用户最容易撞到、也最容易被投诉的一类错误：把实际返回的文案记进请求日志，
        // 客服拿着用户截图才能对上「他看到的到底是哪一句」。用 body 上的值而不是入参，
        // 是因为上面的 keepCjkOr 会改写 message。
        LogContext.recordError(body.code(), body.message(), null);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                // 与 errorResponse 同理：429 可能出在 SSE 端点上，错误体必须是 JSON 且不受
                // Accept 影响（见 errorResponse 的说明）。
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private ResponseEntity<ApiError> errorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> details) {
        return errorResponse(status, message, request, details, codeForStatus(status));
    }

    /**
     * 带业务码的响应：{@code code} 走 {@link ErrorCode}，前端按码归类。
     *
     * <p><b>必须显式写 {@code Content-Type}，不能只把 body 交给转换器</b>：错误体是 JSON，
     * 但出错的端点未必「只会写 JSON」。SSE 接口在 mapping 上声明了
     * {@code produces = text/event-stream}，Spring 会把它记进请求的可写媒体类型，
     * 写响应时再拿它和 {@code Accept} 协商。客户端只发 {@code Accept: text/event-stream}
     * （SSE 客户端的标准写法）时，JSON 错误体协商不出可接受的类型，抛
     * {@code HttpMediaTypeNotAcceptableException}，401 被兜成 500 + 空响应体 ——
     * 调用方拿不到「请先登录」，只看到一个没有原因的服务器错误。</p>
     *
     * <p>预设了具体（concrete）的 {@code Content-Type} 后，Spring 直接走「媒体类型已定」
     * 分支，跳过 Accept / produces 协商：错误响应对任何客户端都是同一份 JSON，
     * 与端点正常返回什么格式无关。</p>
     */
    private ResponseEntity<ApiError> errorResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> details,
            String code) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details,
                code);
        // 全站错误响应的唯一收敛点：把返回给调用方的 code / message / 字段明细一并记进请求日志，
        // 这样「用户看到了什么」和「服务端认为发生了什么」永远是同一份数据，不需要二次推断。
        LogContext.recordError(code, message, details);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 只靠状态码能确定的业务码。
     *
     * <p>这是「比 {@code String.valueOf(status)} 好」的默认值：状态码本身已经说明了
     * 401/403/404 这类语义，前端拿码就能归类，不用再靠正则猜。上游失败这类状态码
     * 说不清的原因，由 {@code handleLlmFailure} 等显式覆盖。</p>
     */
    static String codeForStatus(HttpStatus status) {
        return switch (status.value()) {
            case 400, 422 -> ErrorCode.INVALID_INPUT;
            // 413 有专属码，就别压成「参数不合法」：文件太大能直接告诉用户去压缩，
            // 说「参数有误」等于让他自己猜哪里错了。
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            // 415 是「媒体类型不支持」，仍然属于「改一改输入再发」的范畴，
            // 归 INVALID_INPUT 而不是 UNKNOWN——UNKNOWN 会让前端退化成场景兜底文案。
            case 415 -> ErrorCode.INVALID_INPUT;
            case 401 -> ErrorCode.AUTH_EXPIRED;
            case 402 -> ErrorCode.INSUFFICIENT_QUOTA;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 428 -> ErrorCode.CONFIRM_PASSWORD;
            case 429 -> ErrorCode.RATE_LIMITED;
            // 502/504 是上游网关/超时，语义等同于「依赖服务不可用」，
            // 归 UNKNOWN 会让用户看到「操作失败」这种没法行动的话。
            case 502, 503, 504 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> ErrorCode.UNKNOWN;
        };
    }

    /**
     * 管理端（{@code /api/admin/**}）保留业务异常原文，便于对照校验规则排查；
     * C 端继续走统一短中文，避免英文/实现细节泄漏。
     *
     * <p>登录态类异常（401）故意不走这条：admin 与 C 端都应看到统一的登录失效提示。</p>
     */
    static String adminOrUserFacing(
            HttpServletRequest request,
            String message,
            String blankFallback,
            java.util.function.Function<String, String> userFacing) {
        if (isAdminApi(request)) {
            if (message == null || message.isBlank()) {
                return blankFallback;
            }
            return message.strip();
        }
        return userFacing.apply(message);
    }

    static boolean isAdminApi(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return false;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.startsWith("/api/admin/");
    }

    /** Token / 会话类英文 → 统一登录失效；登录凭证错误单独保留；已有中文业务句原样。 */
    static String userFacingUnauthorized(String message) {
        if (message == null || message.isBlank()) {
            return ApiMessages.LOGIN_EXPIRED;
        }
        String lower = message.toLowerCase();
        if (lower.contains("incorrect username or password")
                || lower.contains("invalid credentials")) {
            return ApiMessages.INVALID_CREDENTIALS;
        }
        if (lower.contains("current password is incorrect")
                || lower.contains("account password is incorrect")) {
            return ApiMessages.CURRENT_PASSWORD_INCORRECT;
        }
        if (HAS_CJK.matcher(message).find()) {
            return message.strip();
        }
        return ApiMessages.LOGIN_EXPIRED;
    }

    /** 面向用户的文案是否属于「凭证错误」而非「会话失效」。 */
    static boolean isInvalidCredentialsFacing(String facingMessage) {
        return ApiMessages.INVALID_CREDENTIALS.equals(facingMessage)
                || ApiMessages.CURRENT_PASSWORD_INCORRECT.equals(facingMessage);
    }

    static String userFacingForbidden(String message) {
        if (message == null || message.isBlank()) {
            return ApiMessages.FORBIDDEN;
        }
        String lower = message.toLowerCase();
        if (lower.contains("not active") || lower.contains("disabled") || message.contains("已禁用")) {
            return ApiMessages.ACCOUNT_DISABLED;
        }
        if (HAS_CJK.matcher(message).find()) {
            return message.strip();
        }
        return ApiMessages.FORBIDDEN;
    }

    static String userFacingNotFound(String message) {
        if (message != null && HAS_CJK.matcher(message).find()) {
            return message.strip();
        }
        return ApiMessages.NOT_FOUND;
    }

    /**
     * 400：已有中文业务句保留；纯英文统一成「请求参数有误」，避免 Jackson/约束原文泄漏。
     */
    /**
     * 中文原话已经是给用户看的（业务代码主动写的），保留；英文/空则用兜底文案。
     *
     * <p>与 {@link #userFacingClientMessage} 的区别：那个用于 400/403 这类
     * 「内部原因不该外泄」的场景，这里用于 413/429 —— 这两个状态码的文案本来就是
     * 我们写给用户的，归一反而丢信息。</p>
     */
    public static String keepCjkOr(String message, String fallback) {
        if (message != null && HAS_CJK.matcher(message).find()) {
            return message.strip();
        }
        return fallback;
    }

    static String userFacingClientMessage(String message) {
        if (message != null && HAS_CJK.matcher(message).find()) {
            return message.strip();
        }
        return ApiMessages.BAD_REQUEST;
    }

    static String userFacingConflict(String message) {
        if (message != null && HAS_CJK.matcher(message).find()) {
            return message.strip();
        }
        return ApiMessages.CONFLICT;
    }

    /**
     * 503：英文/配置类归一；中文业务提示保留（例如「模型没有返回可用图片」）。
     * 含路径、exception 原文拼接的中文句由抛出点先改干净。
     */
    static String userFacingServiceUnavailable(String message) {
        return UpstreamFailureText.describe(message, null, ApiMessages.SERVICE_UNAVAILABLE);
    }
}
