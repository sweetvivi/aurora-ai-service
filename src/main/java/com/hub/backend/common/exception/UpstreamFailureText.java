package com.hub.backend.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a gateway / async-task failure into the one short Chinese sentence a user can act on.
 *
 * <p>Every LLM/image/video client in {@code infra.llm} wraps a non-2xx answer as
 * {@code "<what> gateway returned HTTP 400: {\"error\":{\"message\":\"…\"}}"} — fine for logs,
 * not for toasts or gallery cards. Call {@link #describe} (or {@link #describeAsync}) before
 * writing {@code ApiError.message}, SSE {@code message}, or async {@code errorMessage}.</p>
 *
 * <p>Raw upstream text stays in server logs only. Parsing is forgiving: truncated JSON falls
 * through keyword rules and finally to {@link #GENERIC_TEXT} rather than leaking English.</p>
 */
public final class UpstreamFailureText {

    /** Long enough for a short Chinese sentence; toasts and cards stay readable. */
    private static final int MAX_LENGTH = 200;

    /**
     * 上游"该给结构化产物却回了一段文字"的业务码。
     *
     * <p>它<strong>不是</strong>审核的同义词：图片模型判定内容政策不通过时会带它，但模型没被路由到、
     * 不支持该请求、或对话链路上本该出 tool call 却回了正文时，同样带它。所以只认码会把「模型没
     * 出图」说成「内容违规」，让用户去改一个本来没问题的提示词——文案要看报文里到底有没有审核语义。</p>
     */
    private static final String UPSTREAM_TEXT_REPLY = "upstream_text_reply";

    /** 上游认识这个 key，但当前 key 的分组里没有调用权限。 */
    private static final String MODEL_NOT_FOUND = "model_not_found";

    private static final String MODEL_PRICE_ERROR = "model_price_error";

    private static final String INSUFFICIENT_USER_QUOTA = "insufficient_user_quota";

    /** 全站统一：余额 / 额度不足。 */
    public static final String INSUFFICIENT_BALANCE_TEXT = "余额不足，请充值后再试";

    /** 全站统一：内容审核拦截（图 / 视频 / 画布共用）。 */
    public static final String CONTENT_POLICY_TEXT = "内容未通过审核，请修改提示词或更换参考图后重试";

    /**
     * 上游只回了一段文字、没有给出产物。
     *
     * <p>措辞刻意不提「审核」「参考图」：纯对话链路（对话模型、超级智能体）也会命中这个 code，
     * 在那里说「更换参考图」会让用户完全摸不着头脑。</p>
     */
    public static final String TEXT_REPLY_TEXT = "模型没有返回有效结果，请重试或更换模型";

    /** 全站统一：模型不可用 / 未定价 / 上游 422。 */
    public static final String MODEL_UNAVAILABLE_TEXT = "该模型暂不可用，请换模型或稍后重试";

    /** 全站统一：超时。 */
    public static final String TIMEOUT_TEXT = "生成超时，请稍后重试";

    /** 全站统一：结果丢失 / 下载失败。 */
    public static final String RESULT_MISSING_TEXT = "结果获取失败，请重试";

    /** 全站统一：系统繁忙（内网地址等运维问题）。 */
    public static final String BUSY_TEXT = "系统繁忙，请稍后重试";

    /**
     * 供应商集群过载（{@code system_cpu_overloaded} 这类）。
     *
     * <p>与请求内容无关，稍后重试即可；单独一句话是为了不让用户以为是自己写错了提示词。</p>
     */
    public static final String CAPACITY_TEXT = "模型服务繁忙，请稍后重试";

    /** 全站统一：登录同步问题。 */
    public static final String LOGIN_SYNC_TEXT = "登录状态未同步，请重新登录后再试";

    /** 全站统一：登录失效。 */
    public static final String LOGIN_EXPIRED_TEXT = "登录已失效，请重新登录";

    /** 全站统一：限流。 */
    public static final String RATE_LIMIT_TEXT = "请求过于频繁，请稍后再试";

    /** 全站统一：未知失败。 */
    public static final String GENERIC_TEXT = "发生错误，请稍后重试";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final java.util.regex.Pattern HAS_CJK =
            java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]");

    private UpstreamFailureText() {
    }

    /**
     * 上游失败的归因结果。只描述「原因」，不描述 HTTP 语义。
     *
     * <p>它比文案更细（{@code MODEL_UNAVAILABLE} 与 {@code TIMEout} 都归 {@code UPSTREAM_FAILED}
     * 类动作），因为 handler 要据此填业务码，让前端不必再拿关键词猜。</p>
     */
    public enum Kind {
        /** 内容审核拦截。 */
        CONTENT_BLOCKED,
        /** 余额 / 额度不足。 */
        INSUFFICIENT_QUOTA,
        /** 模型不可用 / 未定价 / 供应商 key 失效。 */
        MODEL_UNAVAILABLE,
        /** 上游只回了文字、没给产物；与审核无关。 */
        TEXT_REPLY,
        /** 网关密码未同步。 */
        LOGIN_SYNC_REQUIRED,
        /** 登录已失效（仅限用户会话语义）。 */
        AUTH_EXPIRED,
        /** 超时。 */
        TIMEOUT,
        /** 限流。 */
        RATE_LIMITED,
        /** 产物丢失 / 下载失败。 */
        RESULT_MISSING,
        /** 系统繁忙（内网地址等运维问题）。 */
        BUSY,
        /** 参数不被上游接受（分辨率 / 时长等硬约束），改参数才能成功。 */
        INVALID_REQUEST,
        /** 认不出具体原因。 */
        UPSTREAM_FAILED
    }

    /** 归因 + 面向用户的文案。 */
    public record Description(Kind kind, String message) {
    }

    /**
     * 面向用户的最终文案。
     *
     * @param message      exception / 任务失败原文（可含上游报文）
     * @param upstreamCode 上游 {@code error.code}，可为 null（会尝试从 message 里解析）
     * @param fallback     什么都认不出时的兜底；空则用 {@link #GENERIC_TEXT}
     */
    public static String describe(String message, String upstreamCode, String fallback) {
        return describeWithKind(message, upstreamCode, fallback).message();
    }

    /**
     * 归因 + 文案。handler 用它填 {@code ApiError.code}：文案给用户看，kind 给前端归类。
     *
     * <p>顺序：审核语义词（与 code 无关，因为 {@code upstream_text_reply} 两种原因都带）
     * → 额度 code/文本 → 模型码/文本 → 登录同步 → 登录失效 → 超时 → 限流 → 产物丢失
     * → 内网 → 上游 422 → 内层 message 二次命中 → 中文透传 → 兜底。</p>
     */
    public static Description describeWithKind(String message, String upstreamCode, String fallback) {
        String safeFallback = (fallback == null || fallback.isBlank()) ? GENERIC_TEXT : fallback.strip();
        String code = (upstreamCode == null || upstreamCode.isBlank())
                ? upstreamCode(message)
                : upstreamCode.strip();
        String search = message == null ? "" : message;

        if (UPSTREAM_TEXT_REPLY.equals(code) || isTextReply(search)) {
            /*
             * 同一个 code 覆盖两种完全不同的原因，只能看报文自己怎么说：
             * - 报文里明确写了违规/审核/guardrail → 确实是审核拦截，走审核文案；
             * - 报文只说"没返回图片/返回了文本" → 模型没出产物，跟内容政策无关。
             * 早先这里无条件返回 CONTENT_POLICY_TEXT，导致正常提示词被提示「请更换参考图」。
             */
            return isModeration(search)
                    ? new Description(Kind.CONTENT_BLOCKED, CONTENT_POLICY_TEXT)
                    : new Description(Kind.TEXT_REPLY, TEXT_REPLY_TEXT);
        }
        if (isModeration(search)) {
            return new Description(Kind.CONTENT_BLOCKED, CONTENT_POLICY_TEXT);
        }
        if (INSUFFICIENT_USER_QUOTA.equals(code) || isQuota(search)) {
            return new Description(Kind.INSUFFICIENT_QUOTA, INSUFFICIENT_BALANCE_TEXT);
        }
        if (MODEL_NOT_FOUND.equals(code)
                || MODEL_PRICE_ERROR.equals(code)
                || isModelUnavailable(search)) {
            return new Description(Kind.MODEL_UNAVAILABLE, MODEL_UNAVAILABLE_TEXT);
        }
        if (isLoginSync(search)) {
            return new Description(Kind.LOGIN_SYNC_REQUIRED, LOGIN_SYNC_TEXT);
        }
        if (isLoginExpired(search)) {
            return new Description(Kind.AUTH_EXPIRED, LOGIN_EXPIRED_TEXT);
        }
        if (isTimeout(search)) {
            return new Description(Kind.TIMEOUT, TIMEOUT_TEXT);
        }
        if (isRateLimit(search)) {
            return new Description(Kind.RATE_LIMITED, RATE_LIMIT_TEXT);
        }
        if (isCapacityOverload(search)) {
            return new Description(Kind.BUSY, CAPACITY_TEXT);
        }
        if (isResultMissing(search)) {
            return new Description(Kind.RESULT_MISSING, RESULT_MISSING_TEXT);
        }
        // 上游对参数的硬约束（分辨率 / 时长）：它把可用值直接写在报文里，照抄给用户比
        // 「发生错误，请稍后重试」有用得多——这类错误重试同一个参数永远不会成功。
        String constraint = capabilityConstraint(search);
        if (constraint != null) {
            return new Description(Kind.INVALID_REQUEST, constraint);
        }
        if (isGatewayUnreachable(search)) {
            return new Description(Kind.BUSY, BUSY_TEXT);
        }
        if (isBusyInfra(search)) {
            return new Description(Kind.BUSY, BUSY_TEXT);
        }
        if (isUpstreamReject(search)) {
            return new Description(Kind.MODEL_UNAVAILABLE, MODEL_UNAVAILABLE_TEXT);
        }

        String nested = nestedMessage(search);
        String candidate = nested == null || nested.isBlank()
                ? (search.isBlank() ? "" : search.strip())
                : nested.strip();
        if (candidate.isEmpty()) {
            return new Description(Kind.UPSTREAM_FAILED, safeFallback);
        }
        // 二次命中：内层 message 才是额度 / 审核原文
        if (isQuota(candidate)) {
            return new Description(Kind.INSUFFICIENT_QUOTA, INSUFFICIENT_BALANCE_TEXT);
        }
        if (isModeration(candidate)) {
            return new Description(Kind.CONTENT_BLOCKED, CONTENT_POLICY_TEXT);
        }
        if (isModelUnavailable(candidate)) {
            return new Description(Kind.MODEL_UNAVAILABLE, MODEL_UNAVAILABLE_TEXT);
        }
        // 已是中文业务句：截断后原样给用户；纯外文不透出
        if (HAS_CJK.matcher(candidate).find()) {
            return new Description(Kind.UPSTREAM_FAILED, truncate(candidate));
        }
        return new Description(
                Kind.UPSTREAM_FAILED,
                safeFallback.equals(GENERIC_TEXT) ? GENERIC_TEXT : safeFallback);
    }

    /**
     * 异步任务 / 无结构化 code 时的便捷入口：自动从报文抽 code，兜底用 {@link #GENERIC_TEXT}。
     */
    public static String describeAsync(String message) {
        return describe(message, upstreamCode(message), GENERIC_TEXT);
    }

    /** {@link #describeAsync} 的归因版。 */
    public static Description describeAsyncWithKind(String message) {
        return describeWithKind(message, upstreamCode(message), GENERIC_TEXT);
    }

    /**
     * @param raw      whatever came out of the client; may be null, blank, or a truncated snippet
     * @param fallback shown when there is nothing usable to quote
     */
    public static String readable(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String message = nestedMessage(raw);
        String text = message == null || message.isBlank() ? raw.strip() : message.strip();
        if (text.isEmpty()) {
            return fallback;
        }
        return truncate(text);
    }

    /**
     * 从上游报文里取 {@code error.code}，取不到返回 null。
     */
    public static String upstreamCode(String raw) {
        JsonNode node = jsonTail(raw);
        if (node == null) {
            return null;
        }
        JsonNode code = node.path("error").path("code");
        if (code.isTextual()) {
            return code.asText();
        }
        JsonNode top = node.path("code");
        return top.isTextual() ? top.asText() : null;
    }

    private static boolean isQuota(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("insufficient_user_quota")
                || lower.contains("insufficient quota")
                || lower.contains("insufficient_quota")
                || lower.contains("insufficient account balance")
                || lower.contains("exceeded your current quota")
                || lower.contains("not enough quota")
                || lower.contains("not enough balance")
                || lower.contains("payment required")
                || lower.contains("out of credits")
                || lower.contains("预扣费额度失败")
                || raw.contains("余额不足")
                || raw.contains("额度不足")
                || raw.contains("配额已用尽");
    }

    /**
     * 内容审核的判定只认「明确的审核短语」，不认有歧义的裸词。
     *
     * <p>与前端 {@code src/errors/index.ts} 的 {@code MODERATION_PATTERN} 语义对齐，
     * 但保留视频中转站的 {@code risk_control} / {@code prohibited material} 这两种真实审核形态
     * （图片链路不会出现，视频链路是明确审核语义，不会误伤）。</p>
     *
     * <p>刻意剔除的裸词：{@code blocked} / {@code rejected} / {@code sensitive}（裸词）/
     * {@code safety}（裸词）/ {@code violat}（裸词）/ {@code not allowed} /
     * {@code inappropriat}。它们会误伤：{@code account blocked} 是账号封禁，
     * {@code request rejected: invalid token} 是参数错误，一句正常提示词被模型拒出图时也常带
     * 这些词，判成「内容未通过审核」会把用户引向改提示词的死路。</p>
     */
    private static boolean isModeration(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile(
                        "nud|sexual|er[oó]ti[ck]|nudez|pornograph|guardrail|"
                                + "content[\\s_-]*polic|content[\\s_-]*moderat|"
                                + "safety[\\s_-]*(?:filter|system|check)|flagged|"
                                + "sensitive[\\s_-]*content|"
                                + "violat\\w*\\s+(?:our\\s+|the\\s+)?(?:content|safety|usage|community)\\s+polic|"
                                + "prohibited[\\s_-]*(?:content|material)|"
                                + "risk[\\s_-]*control|"
                                + "内容审核|内容未通过审核|未通过审核|敏感内容|违规内容|裸露|色情|情色|低俗",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw)
                .find();
    }

    /** 报文里带了「上游只回了文字」的标记；与是否违规无关。 */
    private static boolean isTextReply(String raw) {
        return raw != null && raw.contains(UPSTREAM_TEXT_REPLY);
    }

    /**
     * 上游把可用参数写在报文里的硬约束。
     *
     * <p>实测 api.demo-relay.online：{@code resolution must be 720p for seedance-2.5-omni}、
     * {@code seedance2.5 duration must be 30 seconds}。这类错误改参数就能成功，重试原参数永远不行，
     * 所以直接把上游给的值翻译成一句能照做的话。</p>
     */
    private static String capabilityConstraint(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.regex.Matcher resolution =
                java.util.regex.Pattern.compile("(?i)resolution must be ([0-9]{3,4}p)").matcher(raw);
        if (resolution.find()) {
            return "该模型只支持 " + resolution.group(1) + " 分辨率，请改成 " + resolution.group(1) + " 后重试";
        }
        java.util.regex.Matcher duration =
                java.util.regex.Pattern.compile("(?i)duration must be ([0-9]{1,3})\\s*(?:seconds|second|s)\\b")
                        .matcher(raw);
        if (duration.find()) {
            return "该模型的时长必须是 " + duration.group(1) + " 秒，请调整后重试";
        }
        return null;
    }

    private static boolean isModelUnavailable(String raw) {        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("model_not_found")
                || lower.contains("model_price_error")
                || lower.contains("has not been priced")
                || lower.contains("not been priced")
                || lower.contains("no longer available")
                || lower.contains("model not available")
                || lower.contains("model is unavailable")
                // 站点插件没给该模型配 channel 映射（如 newbanana 的 model_mapping）：
                // 请求在插件层就被拒，换模型才有意义，重试同一个模型永远不会成功。
                || lower.contains("plugin_request_invalid")
                || lower.contains("buildsubmitrequest failed")
                || lower.contains("configure channel model_mapping")
                // 上游供应商的 key 失效是「模型暂时走不通」的运维问题，不是用户会话失效：
                // 用户重新登录解决不了，换模型或稍后重试才有意义（与前端 MODEL_UNAVAILABLE_PATTERN 对齐）。
                || lower.contains("invalid api key")
                || lower.contains("invalid_api_key")
                || lower.contains("authentication error")
                || lower.contains("authentication_error")
                || raw.contains("该模型不可用")
                || raw.contains("模型暂不可用");
    }

    private static boolean isLoginSync(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("not synchronis")
                || lower.contains("not synchronized")
                || lower.contains("sign in again")
                || raw.contains("登录状态未同步")
                || raw.contains("密码未同步");
    }

    private static boolean isLoginExpired(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        // 只认「用户会话」语义。上游供应商的 key 失效（invalid token / invalid api key /
        // authentication failed / unauthorized）是运维问题，已归到 isModelUnavailable，
        // 不能在这里判成「登录已失效」——用户重新登录解决不了上游 key 失效。
        return raw.contains("登录已失效")
                || raw.contains("请重新登录")
                || raw.contains("未登录");
    }

    private static boolean isTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("timed out")
                || lower.contains("timeout")
                || raw.contains("生成超时")
                || raw.contains("请求超时");
    }

    private static boolean isRateLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("rate limit")
                || lower.contains("rate_limit")
                || lower.contains("too many requests")
                || raw.contains("请求过于频繁");
    }

    /**
     * 供应商算力过载。
     *
     * <p>线上真实形态：中转把供应商的 {@code system_cpu_overloaded} 包成
     * 「发生错误，请稍后重试：system_cpu_overloaded」——前半句是中文，于是会命中
     * 「中文业务句原样透传」那条兜底，把半截英文码一起显示给用户。这类错误与请求内容无关、
     * 几十秒后重试通常就好，所以在这里先截住，换成一句可操作的中文。</p>
     */
    private static boolean isCapacityOverload(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("system_cpu_overload")
                || lower.contains("system_memory_overload")
                || lower.contains("system_gpu_overload")
                || lower.contains("overloaded")
                || lower.contains("overload")
                || lower.contains("server_busy")
                || lower.contains("server busy")
                || lower.contains("service_unavailable")
                || lower.contains("temporarily unavailable")
                || raw.contains("模型服务繁忙")
                || raw.contains("服务繁忙");
    }

    private static boolean isResultMissing(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("without a result url")
                || lower.contains("carried no video url")
                || lower.contains("no video url")
                || lower.contains("download returned http 404")
                // A finished job whose artifact refuses the download key: the video exists, so this
                // is "could not be fetched", not a generation failure the user should re-prompt for.
                || lower.contains("download returned http 401")
                || lower.contains("download returned http 403")
                || lower.contains("download returned http 410")
                || lower.contains("could not be read")
                || lower.contains("could not be stored")
                || raw.contains("结果获取失败")
                || raw.contains("文件已不存在");
    }

    /**
     * 网关本身连不上（连接被拒 / 进程没起 / 端口不对），与提示词、模型、余额都无关。
     *
     * <p>原文形如 {@code The video request could not be submitted: New API is unavailable}，
     * 由 {@code NewApiManagementClient} 把 {@link java.io.IOException} 包成
     * {@code ServiceUnavailableException} 后一路带上来。以前这里没有对应规则，最后落到
     * 「发生错误，请稍后重试」，用户既不知道是自己的问题还是服务的问题，运维也不知道该去查网关。</p>
     */
    private static boolean isGatewayUnreachable(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("could not be submitted")
                || lower.contains("new api is unavailable")
                || lower.contains("failed to connect")
                || lower.contains("connection refused")
                || lower.contains("connectexception")
                || raw.contains("无法连接");
    }

    private static boolean isBusyInfra(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("non-public address")
                || lower.contains("127.0.0.1")
                || lower.contains("localhost")
                || raw.contains("内网地址");
    }

    private static boolean isUpstreamReject(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("upstream_error")
                || lower.contains("xai upstream")
                || lower.contains("http 422");
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LENGTH) + "…";
    }

    private static String nestedMessage(String raw) {
        JsonNode node = jsonTail(raw);
        if (node == null) {
            return null;
        }
        JsonNode nested = node.path("error").path("message");
        if (nested.isTextual()) {
            return nested.asText();
        }
        JsonNode direct = node.path("message");
        return direct.isTextual() ? direct.asText() : null;
    }

    private static JsonNode jsonTail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int brace = raw.indexOf('{');
        if (brace < 0) {
            return null;
        }
        try {
            return MAPPER.readTree(raw.substring(brace));
        } catch (Exception notJson) {
            return null;
        }
    }
}
