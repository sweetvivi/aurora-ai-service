package com.hub.backend.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * Prints a single, greppable banner once the context is fully up.
 *
 * <p>Used as the "did it actually start" marker when reading a long console: the standard
 * "Started ... in N seconds" line scrolls past among hundreds of INFO lines, and when the
 * application is launched from an IDE it is easy to mistake a partial startup for a success.</p>
 */
@Component
public class StartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    private final Environment environment;
    private final DataSource dataSource;

    public StartupBanner(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String profiles = String.join(",", environment.getActiveProfiles());
        String flyway = Boolean.parseBoolean(environment.getProperty("spring.flyway.enabled", "false"))
                ? "enabled"
                : "disabled";

        log.info("""

                ============================================================
                  BACKEND SERVICE STARTED SUCCESSFULLY
                ------------------------------------------------------------
                  url       : http://localhost:{}
                  profiles  : {}
                  database  : {}
                  flyway    : {}
                  api docs  : http://localhost:{}/doc.html
                ============================================================""",
                port,
                profiles.isEmpty() ? "default" : profiles,
                describeDatabase(),
                flyway,
                port);
    }

    /**
     * Asks the live connection who it actually is.
     *
     * <p>Reading {@code spring.datasource.url} would only echo the configured placeholder;
     * a pool or a profile override can point the connection somewhere else. The JDBC metadata
     * comes from the server that answered, so it is the address really in use. Credentials and
     * query parameters are stripped before logging.</p>
     */
    private String describeDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String product = metaData.getDatabaseProductName();
            String version = metaData.getDatabaseProductVersion();
            return "%s %s @ %s".formatted(product, version, stripCredentials(url));
        } catch (Exception exception) {
            log.warn("Could not read database metadata for the startup banner", exception);
            return "unknown";
        }
    }

    private String stripCredentials(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        int query = url.indexOf('?');
        String trimmed = query >= 0 ? url.substring(0, query) : url;
        // jdbc:mysql://user:password@host/db should never be printed with the password intact.
        int scheme = trimmed.indexOf("://");
        int at = trimmed.lastIndexOf('@');
        if (scheme < 0 || at < scheme) {
            return trimmed;
        }
        return trimmed.substring(0, scheme + 3) + trimmed.substring(at + 1);
    }
}
