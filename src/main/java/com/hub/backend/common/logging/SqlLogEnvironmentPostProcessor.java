package com.hub.backend.common.logging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 日志开关：{@code app.sql-log.enabled} 一个布尔值，决定 Hibernate 的 SQL 与绑定参数是否输出。
 *
 * <p><b>为什么不用 {@code spring.jpa.show-sql}</b>：它把 SQL 直接打到标准输出，绕过 logback——
 * 于是既进不了 {@code sql.log}，也没法带 traceId，关掉还得重启改配置。走
 * {@code logging.level.org.hibernate.SQL} 才是正路：日志会正常经过 appender、带上 MDC 里的
 * traceId，和访问日志能对上。</p>
 *
 * <p><b>为什么在 EnvironmentPostProcessor 里做而不是用 yml 的 {@code logging.level.*}</b>：
 * yml 里没法「按一个布尔开关决定另一处属性的值」。放在这里，启动最早的阶段就把级别注入环境，
 * 连 Flyway 迁移和 Hibernate 建表（都远早于业务代码）都会按开关走；而且它是普通配置项，
 * 可以被 {@code SQL_LOG_ENABLED=true} 环境变量覆盖，不需要改代码或打包。</p>
 *
 * <p>注入位置在 property source 链的**最末**：yml 里显式写了的 {@code logging.level.*} 优先，
 * 不会被这里覆盖。</p>
 *
 * <p><b>生产环境默认关闭的三个理由</b>：① SQL 日志会把用户对话内容、手机号等明文写进文件
 * （绑定参数原样输出，脱敏器管不到这一层）；② 一次请求几十条语句，I/O 与日志量都成倍增长；
 * ③ 慢查询该用 MySQL 的 slow query log 与 EXPLAIN 定位，而不是靠在应用日志里翻。</p>
 */
public class SqlLogEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_ENABLED = "app.sql-log.enabled";

    /** Hibernate 6 的语句级 logger。 */
    private static final String LOGGER_SQL = "logging.level.org.hibernate.SQL";
    /** Hibernate 6.3+ 的绑定参数 logger（旧版是 org.hibernate.type.descriptor.sql）。 */
    private static final String LOGGER_BIND = "logging.level.org.hibernate.orm.jdbc.bind";
    private static final String LOGGER_BIND_LEGACY = "logging.level.org.hibernate.type.descriptor.sql";
    /** 结果集取值 logger：只在排查「取出来的值不对」时才有用，默认关。 */
    private static final String LOGGER_EXTRACT = "logging.level.org.hibernate.orm.jdbc.extract";
    /** 手写 JdbcTemplate 语句（日志表批量写入、报表查询等），默认关：量大且多是自身的写入。 */
    private static final String LOGGER_JDBC_TEMPLATE = "logging.level.org.springframework.jdbc.core.JdbcTemplate";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(PROPERTY_ENABLED, Boolean.class, false)) {
            return;
        }
        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put(LOGGER_SQL, LogLevel.DEBUG.name());
        if (environment.getProperty("app.sql-log.bind-values", Boolean.class, true)) {
            levels.put(LOGGER_BIND, LogLevel.TRACE.name());
            levels.put(LOGGER_BIND_LEGACY, LogLevel.TRACE.name());
        }
        if (environment.getProperty("app.sql-log.result-values", Boolean.class, false)) {
            levels.put(LOGGER_EXTRACT, LogLevel.TRACE.name());
        }
        if (environment.getProperty("app.sql-log.jdbc-template", Boolean.class, false)) {
            levels.put(LOGGER_JDBC_TEMPLATE, LogLevel.DEBUG.name());
        }
        // format_sql 让语句换行缩进，否则一条 JOIN 是一整行，肉眼根本读不动。
        // 它是 EntityManagerFactory 构建时读取的 JPA 属性，正好也在环境变量里生效。
        if (environment.getProperty("app.sql-log.format", Boolean.class, true)) {
            levels.put("spring.jpa.properties.hibernate.format_sql", "true");
        }
        environment.getPropertySources().addLast(new MapPropertySource("sqlLogLevels", levels));
    }

    @Override
    public int getOrder() {
        // 必须晚于配置文件加载（ConfigDataEnvironmentPostProcessor 是 HIGHEST_PRECEDENCE + 10），
        // 否则读不到 yml 里的 app.sql-log.enabled，开关永远是 false。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
