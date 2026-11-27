package com.hub.backend.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 本进程的实例标识，写进每一条日志。
 *
 * <p>为什么不是只用 PID：容器里每个副本的 JVM 都是从 1 号开始，PID 在多副本下几乎不区分任何东西。
 * 主机名（容器里是容器 id / compose 服务名 + 序号）才是那个「这个请求是谁处理的」的答案。</p>
 *
 * <p>取值顺序：{@code APP_INSTANCE} 环境变量 → {@code HOSTNAME}（Docker 注入）→
 * {@code COMPUTERNAME}（Windows）→ 主机名 → {@code unknown}，最后拼上 PID。</p>
 */
public final class InstanceIdentity {

    /** 供 logback 读取的系统属性名。 */
    public static final String PROPERTY = "app.instance";

    private InstanceIdentity() {
    }

    public static String resolve() {
        String override = System.getenv("APP_INSTANCE");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return hostname() + ":" + ProcessHandle.current().pid();
    }

    private static String hostname() {
        for (String candidate : new String[]{
                System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME")}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "unknown";
        }
    }
}
