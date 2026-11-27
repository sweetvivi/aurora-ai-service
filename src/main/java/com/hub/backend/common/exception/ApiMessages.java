package com.hub.backend.common.exception;

/**
 * C 端用户可见的短中文文案。
 *
 * <p>业务代码抛异常时优先引用这里；{@code GlobalExceptionHandler} 也会把英文/泄漏原文
 * 归一到这些常量，避免前后端各维护一套关键词表。</p>
 */
public final class ApiMessages {

    private ApiMessages() {
    }

    /** 会话失效 / access·refresh token 问题。 */
    public static final String LOGIN_EXPIRED = UpstreamFailureText.LOGIN_EXPIRED_TEXT;

    /** 需要登录才能继续（游客触达计费能力等）。 */
    public static final String LOGIN_REQUIRED = "请先登录后再试";

    /** 用户名或密码错误（登录页）。 */
    public static final String INVALID_CREDENTIALS = "用户名或密码错误";

    /** 当前密码不正确（改密等）。 */
    public static final String CURRENT_PASSWORD_INCORRECT = "当前密码不正确";

    /** 账号被禁用。 */
    public static final String ACCOUNT_DISABLED = "账号已被禁用，请联系客服";

    /** 资源不存在。 */
    public static final String NOT_FOUND = "内容不存在或已被删除";

    /** 参数校验失败（顶层 message；字段细节放 details）。 */
    public static final String BAD_REQUEST = "请求参数有误";

    /** 唯一约束 / 重复绑定等冲突。 */
    public static final String CONFLICT = "数据与现有记录冲突，请检查后重试";

    /** 视频历史：仍在跑的任务不能直接删。 */
    public static final String VIDEO_TASK_STILL_RUNNING = "任务仍在生成中，请先取消再移除";

    /** 请求体无法解析。 */
    public static final String BODY_UNREADABLE = "请求体无法解析";

    /** 运维/配置类 503 / 网关 502·504：与 {@link UpstreamFailureText#BUSY_TEXT} 统一。 */
    public static final String SERVICE_UNAVAILABLE = UpstreamFailureText.BUSY_TEXT;

    /** 通用拒绝。 */
    public static final String FORBIDDEN = "请求被拒绝，请稍后重试";

    /** 附件/存储失败。 */
    public static final String ATTACHMENT_FAILED = "附件处理失败，请稍后重试";

    /** 上传过大。 */
    public static final String UPLOAD_TOO_LARGE = "附件超过允许的大小";

    /** 上传失败（不附带底层原因）。 */
    public static final String UPLOAD_FAILED = "上传失败，请稍后重试";

    /** JSON 字段不合法。 */
    public static final String INVALID_JSON = "数据格式不正确";

    /** 编排/生成类内部失败。 */
    public static final String GENERATION_FAILED = "生成失败，请稍后重试";

    /** 428：会话仍有效，但需要再次确认账号密码。 */
    public static final String PASSWORD_CONFIRM_REQUIRED =
            "当前会话需要确认账号密码后才能继续";

    /** 登录态与 New API 未同步。 */
    public static final String LOGIN_SYNC = UpstreamFailureText.LOGIN_SYNC_TEXT;

    /** 用户名长度。 */
    public static final String USERNAME_LENGTH = "用户名长度需为 3–20 个字符";

    /** 用户名字符集。 */
    public static final String USERNAME_PATTERN = "用户名仅支持字母、数字与 _ . -";

    /** 密码不能为空。 */
    public static final String PASSWORD_REQUIRED = "密码不能为空";

    /** 用户名不能为空。 */
    public static final String USERNAME_REQUIRED = "用户名不能为空";

    /** 对话/智能体：不能同时重新生成与编辑。 */
    public static final String CHAT_EDIT_AND_REGENERATE =
            "不能同时重新生成和编辑，请只选一种操作";

    /** 对话/智能体：重新生成或编辑需要会话上下文。 */
    public static final String CHAT_EDIT_REQUIRES_CONVERSATION =
            "请先打开对话后再重新生成或编辑";

    /** 只能对助手回复重新生成。 */
    public static final String CHAT_REGENERATE_TARGET =
            "只能对助手回复重新生成";

    /** 只能编辑自己的提问。 */
    public static final String CHAT_EDIT_TARGET =
            "只能编辑自己的提问";

    /** 429：通用限流，与上游兜底文案保持一致。 */
    public static final String RATE_LIMITED = UpstreamFailureText.RATE_LIMIT_TEXT;

    /** 429：登录失败次数超限。文案与账号是否存在无关，避免变成枚举工具。 */
    public static final String LOGIN_RATE_LIMITED = "登录尝试过于频繁，请稍后再试";

    /** 429：同一 IP 注册过于频繁。 */
    public static final String REGISTRATION_RATE_LIMITED = "注册过于频繁，请稍后再试";

    /** 429：同一 IP 取验证码过于频繁。 */
    public static final String CODE_RATE_LIMITED = "请求验证码过于频繁，请稍后再试";

    /** 429：同一邮箱取验证码过于频繁。 */
    public static final String CODE_MAILBOX_RATE_LIMITED = "该邮箱请求验证码过于频繁，请稍后再试";
}
