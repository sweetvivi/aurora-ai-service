package com.hub.backend.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 把「这次是拿哪份配置、连哪个库、跑在哪个版本上起来的」写进 {@code startup.log}。
 *
 * <p>Spring Boot 自己的启动日志偏过程（谁初始化了、谁跳过了），但不回答运维最常问的三件事：
 * 这次用的哪个 profile、连的是哪个库、数据库迁移到了哪一版。容器化部署之后日志随容器消失，
 * 事后复盘往往只剩一句「当时是好的」，这些信息就长期缺位。</p>
 *
 * <p>输出到独立的 logger {@code STARTUP}，由 logback-spring.xml 路由到 startup.log，
 * 不进 backend.log——启动摘要是「一次事件」而不是「一条业务流」，混在业务日志里只会被冲掉。</p>
 */
@Component
public class StartupReporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger startup = LoggerFactory.getLogger("STARTUP");

    /** Flyway 版本号列可能为空（空库首次迁移前），用固定 SQL 而不是依赖 flyway 的内部 API。 */
    private static final String FLYWAY_VERSION_SQL =
            "SELECT MAX(CAST(version AS DECIMAL(20,4))) FROM flyway_schema_history WHERE success = 1";

    private final Environment environment;
    private final RequestLogProperties requestLogProperties;
    private final JdbcTemplate jdbc;

    private final Instant bootAt = Instant.now();

    public StartupReporter(Environment environment,
                           RequestLogProperties requestLogProperties,
                           JdbcTemplate jdbc) {
        this.environment = environment;
        this.requestLogProperties = requestLogProperties;
        this.jdbc = jdbc;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 多行统一前缀，grep 'startup ' startup.log 就能把一次启动的所有信息拉出来。
        startup.info("startup begin");
        startup.info("startup app={} version={} profile={} pid={}",
                environment.getProperty("spring.application.name", "backend-service"),
                implementationVersion(),
                String.join(",", activeProfiles()),
                pid());
        startup.info("startup server port={} contextPath={} logDir={}",
                environment.getProperty("local.server.port",
                        environment.getProperty("server.port", "8080")),
                environment.getProperty("server.servlet.context-path", "/"),
                environment.getProperty("logging.file.path", "./logs"));
        startup.info("startup datasource url={} poolMax={}",
                maskJdbcUrl(environment.getProperty("spring.datasource.url", "")),
                environment.getProperty("spring.datasource.hikari.maximum-pool-size", "default"));
        startup.info("startup jdbcType={} flyway={} schemaVersion={}",
                jdbcType(),
                environment.getProperty("spring.flyway.enabled", "false"),
                schemaVersion());
        startup.info("startup jvm={} vendor={} heapMax={}MB os={} {}",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
        startup.info("startup features requestLog={} auditLog={} sqlLog={} consoleLevel={}",
                onOff(requestLogProperties.isEnabled()),
                onOff(bool("app.audit-log.enabled", true)),
                onOff(bool("app.sql-log.enabled", false)),
                environment.getProperty("app.log.console-level", "DEBUG"));
        // 保留期写进启动摘要：清理任务删多了不好查，先让「当前生效的是几天」可被看见。
        startup.info("startup retention requestLogDays={} auditLogDays={}",
                requestLogProperties.getRetentionDays(),
                environment.getProperty("app.audit-log.retention-days", "180"));
        startup.info("startup elapsed={}ms", Duration.between(bootAt, Instant.now()).toMillis());
        // 显式分隔线：同一个 startup.log 里可能有多轮启动（容器内重启、本地反复重启），
        // 没有结束标记时无法判断后面是「进程还在写」还是「下一轮的日志」。
        startup.info("startup ready");
    }

    /**
     * Spring Boot 打印的是业务日志的端口；容器里真正可用的是运行时绑定的端口，两者在
     * {@code server.port=0}（随机端口，测试常用）时并不一致。
     */
    private List<String> activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? List.of(environment.getDefaultProfiles()) : Arrays.asList(profiles);
    }

    private String schemaVersion() {
        if (!Boolean.parseBoolean(environment.getProperty("spring.flyway.enabled", "false"))) {
            return "off";
        }
        try {
            java.math.BigDecimal version = jdbc.queryForObject(FLYWAY_VERSION_SQL, java.math.BigDecimal.class);
            // CAST 成 DECIMAL 会带小数位（99 → 99.0000），去掉尾部零才和 flyway_schema_history 里的值一致。
            return version == null ? "none" : version.stripTrailingZeros().toPlainString();
        } catch (RuntimeException ex) {
            // 迁移表还不存在（首次启动）或权限不足，报错即可，不能因为启动摘要把应用带崩。
            return "unknown";
        }
    }

    private String jdbcType() {
        String driver = environment.getProperty("spring.datasource.driver-class-name", "");
        if (driver.contains("h2")) {
            return "H2";
        }
        if (driver.contains("mysql")) {
            return "MySQL";
        }
        return driver.isBlank() ? "unknown" : driver;
    }

    private boolean bool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(environment.getProperty(key, String.valueOf(defaultValue)));
    }

    private static String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }

    /** JDBC URL 里可能带 {@code user=}/{@code password=}（部分驱动写法），日志里一律抹掉。 */
    private static String maskJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            return "-";
        }
        return url.replaceAll("(?i)(password|pwd|user)=[^&;]*", "$1=***");
    }

    private static String implementationVersion() {
        String version = StartupReporter.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static String pid() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        // "12345@host" 是 JVM 的历史格式，跨平台都有；取 @ 前就是 pid。
        String name = runtime.getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }
}
