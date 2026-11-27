package com.hub.backend.modules.auth;

import com.hub.backend.common.exception.InvalidRequestException;

/** Password length policy measured in Unicode code points, not UTF-16 units or bytes. */
public final class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 8;
    public static final int MAX_CODE_POINTS = 128;

    private PasswordPolicy() {
    }

    /**
     * @param fieldLabel 面向用户的中文字段名（「密码」/「当前密码」/「新密码」）。
     *                   这一层只负责长度规则，拼出的话直接进响应，不要再写英文 ——
     *                   {@code GlobalExceptionHandler} 对含中文的原文原样保留，对纯英文
     *                   一律归一成「请求参数有误」，用户就看不到到底哪里不对了。
     */
    public static void requireValid(String password, String fieldLabel) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < MIN_CODE_POINTS || length > MAX_CODE_POINTS) {
            throw new InvalidRequestException(
                    "%s长度需为 %d–%d 个字符".formatted(fieldLabel, MIN_CODE_POINTS, MAX_CODE_POINTS));
        }
    }
}
