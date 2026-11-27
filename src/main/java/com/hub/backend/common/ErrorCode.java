package com.hub.backend.common;

/**
 * 全站统一业务码。
 *
 * <p>写入 {@link ApiError#code}，供前端按码归类，替代「用正则猜错误原因」的过渡方案。
 * 码一旦发布就当作对外契约：只增不改名，语义变了要换新码。</p>
 *
 * <p><b>为什么需要它。</b>以前 {@code code} 填的是 {@code String.valueOf(status)}，
 * 200/400/500 只是 HTTP 语义，说不清「是审核拦截还是参数错误」。前端只好拿关键词去猜，
 * 于是「正常提示词被模型拒出图」会被猜成「内容未通过审核」。有了业务码，归类靠码，
 * 文案靠码，文本匹配就只剩给旧响应兜底的份。</p>
 *
 * <p>命名与前端 {@code src/errors/kinds.ts} 的 {@code ErrorKind} 保持可映射关系。</p>
 */
public final class ErrorCode {

    /* -------------------------- 用户可处理 -------------------------- */

    /** 内容审核拦截：改提示词或换参考图。 */
    public static final String CONTENT_BLOCKED = "CONTENT_BLOCKED";

    /** 余额 / 额度不足：去充值。 */
    public static final String INSUFFICIENT_QUOTA = "INSUFFICIENT_QUOTA";

    /** 请求参数不合法：改输入。 */
    public static final String INVALID_INPUT = "INVALID_INPUT";

    /** 需要确认账号密码（428）。 */
    public static final String CONFIRM_PASSWORD = "CONFIRM_PASSWORD";

    /* ---------------------------- 登录 / 权限 ---------------------------- */

    /** 登录态失效：重新登录。 */
    public static final String AUTH_EXPIRED = "AUTH_EXPIRED";

    /** 有身份但无权限：联系管理员。 */
    public static final String FORBIDDEN = "FORBIDDEN";

    /** 网关密码未同步：重新登录。 */
    public static final String LOGIN_SYNC_REQUIRED = "LOGIN_SYNC_REQUIRED";

    /* ------------------------------ 上游 ------------------------------ */

    /** 模型不可用 / 未定价 / 供应商 key 失效：换模型或稍后重试。 */
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    /** 生成超时。 */
    public static final String TIMEOUT = "TIMEOUT";

    /** 限流：等 Retry-After 秒。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** 产物丢失 / 下载失败。 */
    public static final String RESULT_MISSING = "RESULT_MISSING";

    /** 上游失败但归不出具体原因：重试。 */
    public static final String UPSTREAM_FAILED = "UPSTREAM_FAILED";

    /** 依赖服务不可用（内网地址、未启用等运维问题）。 */
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    /** 系统繁忙。 */
    public static final String BUSY = "BUSY";

    /* ------------------------------ 通用 ------------------------------ */

    public static final String NOT_FOUND = "NOT_FOUND";

    public static final String CONFLICT = "CONFLICT";

    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /** 无法归因。 */
    public static final String UNKNOWN = "UNKNOWN";

    private ErrorCode() {
    }
}
