package com.hub.backend;

import com.hub.backend.common.util.InstanceIdentity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BackendServiceApplication {

    public static void main(String[] args) {
        // 必须在 Spring 启动前写：logback-spring.xml 在读配置时就要拿到它，否则每行日志里
        // 的实例标识会是默认值。多副本时这一列是区分「谁执行的」的唯一依据（容器里 PID 都从 1 起）。
        System.setProperty(InstanceIdentity.PROPERTY, InstanceIdentity.resolve());
        SpringApplication.run(BackendServiceApplication.class, args);
    }
}
