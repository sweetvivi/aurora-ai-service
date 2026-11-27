package com.hub.backend.infra.newapi;

import com.hub.backend.common.exception.ServiceUnavailableException;

/**
 * 哪些 New API 失败值得「过一会儿再来一次」。
 *
 * <p>注销时的远端删除只有两类失败：上游明确拒绝（用户名不存在、密码不对、root 不能删自己），
 * 重试一万次也是同一个答案；以及上游暂时做不了（限流、5xx、超时），那时候账号还在，
 * 只是这一次没删成。后者才进重试队列 —— 把前者也塞进去，只会占着队列并最终变成一堆没用的
 * FAILED 行。</p>
 *
 * <p>放在这里而不是各个调用点，是因为入队判断与重试判断必须用同一把尺子：一次失败被判定
 * 「可重试」而入队，回头重试时又被判定「不可重试」，任务就会在队列里空转到上限。</p>
 */
public final class NewApiRetryPolicy {

    private NewApiRetryPolicy() {
    }

    /** New API 的限流：关键接口 20 次 / 20 分钟，撞上就是 429。 */
    public static boolean isRateLimited(Throwable failure) {
        NewApiHttpException http = httpFailure(failure);
        return http != null && http.statusCode() == 429;
    }

    /**
     * 上游暂时做不成这件事，过一会儿可以再试。
     *
     * <p>包含：限流 429、任意 5xx（网关/上游内部错误）、以及连不上或超时
     * （{@link ServiceUnavailableException}）。不含 4xx —— 那是拒绝，不是忙碌。</p>
     */
    public static boolean isRetryable(Throwable failure) {
        for (Throwable current = failure;
             current != null && current.getCause() != current;
             current = current.getCause()) {
            if (current instanceof ServiceUnavailableException) {
                return true;
            }
            if (current instanceof NewApiHttpException http) {
                return http.statusCode() == 429 || http.statusCode() >= 500;
            }
        }
        return false;
    }

    private static NewApiHttpException httpFailure(Throwable failure) {
        for (Throwable current = failure;
             current != null && current.getCause() != current;
             current = current.getCause()) {
            if (current instanceof NewApiHttpException http) {
                return http;
            }
        }
        return null;
    }
}
