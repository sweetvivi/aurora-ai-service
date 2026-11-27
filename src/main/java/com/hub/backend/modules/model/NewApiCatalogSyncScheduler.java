package com.hub.backend.modules.model;

import com.hub.backend.common.config.properties.NewApiProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Best-effort startup and periodic refresh of the New API catalogue.
 *
 * <p>A temporary New API outage must not prevent this service from starting. Administrators can
 * retry from the model/provider pages, and the last successful cache remains readable meanwhile.
 * The periodic refresh uses the same full snapshot, so provider and model rows cannot drift apart
 * when a channel's key starts seeing a different set of models.</p>
 */
@Component
public class NewApiCatalogSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewApiCatalogSyncScheduler.class);

    private final NewApiCatalogSyncService catalogSync;
    private final NewApiProperties properties;

    public NewApiCatalogSyncScheduler(NewApiCatalogSyncService catalogSync,
                                      NewApiProperties properties) {
        this.catalogSync = catalogSync;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (properties.resolvedBaseUrl().isBlank()) {
            log.info("New API catalogue startup sync is skipped because base-url is empty");
            return;
        }
        if (!properties.isCatalogSyncOnStartup()) {
            log.info("New API catalogue startup sync is disabled");
            return;
        }
        sync("startup");
    }

    /**
     * Refreshes the complete provider/model snapshot. {@code fixedDelay} avoids overlapping syncs
     * when New API is slow; the service is also synchronized as a second guard within one process.
     *
     * <p>进程内的 {@code synchronized} 挡不住第二个副本：两个实例会各拉一遍全量快照、同时
     * upsert 同一批行，最后谁的快照生效取决于提交顺序。{@code @SchedulerLock} 把「同时只有
     * 一个实例在做全量同步」这件事放到库里判定。</p>
     *
     * <p>只锁定时刷新、不锁启动同步：{@link #syncOnStartup()} 要让每个实例在启动后立刻拿到
     * 可用目录，抢不到锁就跳过会让新实例长时间读到空表。</p>
     */
    @SchedulerLock(name = "newapi-catalog-sync", lockAtMostFor = "${app.new-api.catalog-sync-lock-at-most:30m}")
    @Scheduled(
            fixedDelayString = "${app.new-api.catalog-sync-interval-millis:300000}",
            initialDelayString = "${app.new-api.catalog-sync-initial-delay-millis:300000}")
    public void syncPeriodically() {
        if (properties.resolvedBaseUrl().isBlank() || !properties.isCatalogSyncEnabled()) {
            return;
        }
        sync("periodic");
    }

    private void sync(String trigger) {
        try {
            var result = catalogSync.sync();
            log.info(
                    "New API catalogue {} sync completed: {} provider(s), {} model(s); "
                            + "pricing checked={}, templates found={}, created={}, failed={}, "
                            + "still unpriced={}",
                    trigger,
                    result.providersTotal(),
                    result.modelsTotal(),
                    result.pricingChecked(),
                    result.pricingCopied(),
                    result.pricingCreated(),
                    result.pricingFailed(),
                    result.pricingUnpriced());
        } catch (RuntimeException exception) {
            log.warn("New API catalogue {} sync failed; keeping the last local cache: {}",
                    trigger, exception.getMessage());
        }
    }
}
