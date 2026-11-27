package com.hub.backend.modules.model;

import com.hub.backend.common.config.properties.NewApiProperties;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NewApiCatalogSyncSchedulerTest {

    @Test
    void periodicSyncRefreshesTheCompleteCatalogueSnapshot() {
        NewApiCatalogSyncService catalogSync = mock(NewApiCatalogSyncService.class);
        NewApiProperties properties = enabledProperties();
        NewApiCatalogSyncScheduler scheduler =
                new NewApiCatalogSyncScheduler(catalogSync, properties);
        when(catalogSync.sync()).thenReturn(result());

        scheduler.syncPeriodically();

        verify(catalogSync).sync();
    }

    @Test
    void disabledOrUnconfiguredPeriodicSyncDoesNotCallNewApi() {
        NewApiCatalogSyncService catalogSync = mock(NewApiCatalogSyncService.class);
        NewApiProperties disabled = enabledProperties();
        disabled.setCatalogSyncEnabled(false);
        new NewApiCatalogSyncScheduler(catalogSync, disabled).syncPeriodically();

        NewApiProperties noBaseUrl = enabledProperties();
        noBaseUrl.setBaseUrl(" ");
        new NewApiCatalogSyncScheduler(catalogSync, noBaseUrl).syncPeriodically();

        verifyNoInteractions(catalogSync);
    }

    @Test
    void providerAndModelRowsStayOnTheirLastSnapshotWhenPeriodicSyncFails() {
        NewApiCatalogSyncService catalogSync = mock(NewApiCatalogSyncService.class);
        NewApiCatalogSyncScheduler scheduler =
                new NewApiCatalogSyncScheduler(catalogSync, enabledProperties());
        when(catalogSync.sync()).thenThrow(new IllegalStateException("upstream unavailable"));

        assertDoesNotThrow(scheduler::syncPeriodically);

        verify(catalogSync).sync();
    }

    private static NewApiProperties enabledProperties() {
        NewApiProperties properties = new NewApiProperties();
        properties.setCatalogSyncEnabled(true);
        properties.setBaseUrl("https://new-api.example");
        return properties;
    }

    private static NewApiCatalogSyncResponse result() {
        return new NewApiCatalogSyncResponse(
                "NEW_API",
                Instant.parse("2026-10-28T00:00:00Z"),
                3,
                0,
                3,
                0,
                12,
                1,
                11,
                0,
                3,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }
}
