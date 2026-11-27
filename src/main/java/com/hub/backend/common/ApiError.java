package com.hub.backend.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(name = "ApiError", description = "统一错误响应体")
public record ApiError(
        @Schema(description = "错误发生时间（UTC）") Instant timestamp,
        @Schema(description = "HTTP 状态码", example = "400") int status,
        @Schema(description = "HTTP 状态短语", example = "Bad Request") String error,
        @Schema(description = "可读的错误描述", example = "Request validation failed") String message,
        @Schema(description = "触发错误的请求路径", example = "/api/v1/chat/completions") String path,
        @Schema(description = "字段级校验错误明细，键为字段名") Map<String, String> details,
        @Schema(description = "兼容前端错误处理的业务码", example = "400") String code,
        @Schema(description = "兼容前端错误处理的标题", example = "Bad Request") String title,
        @Schema(description = "兼容前端错误处理的错误描述", example = "Request validation failed")
        String msg
) {
    /**
     * 带业务码的构造：{@code code} 用 {@link com.hub.backend.common.ErrorCode} 里的语义码，
     * 前端据此归类，不再拿关键词猜原因。
     */
    public ApiError(Instant timestamp,
                    int status,
                    String error,
                    String message,
                    String path,
                    Map<String, String> details,
                    String code) {
        this(timestamp, status, error, message, path, details, code, error, message);
    }

    /**
     * Keeps the original six-argument construction used by the application exception handlers while
     * publishing the frontend-compatible aliases in every error response.
     *
     * <p>没有业务码时退化为状态码字符串（旧行为），保证既有调用方无需改动。</p>
     */
    public ApiError(Instant timestamp,
                    int status,
                    String error,
                    String message,
                    String path,
                    Map<String, String> details) {
        this(timestamp, status, error, message, path, details,
                String.valueOf(status), error, message);
    }
}
