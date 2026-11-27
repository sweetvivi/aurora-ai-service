package com.hub.backend.modules.admin.model;

import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import com.hub.backend.modules.model.NewApiCatalogSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.admin.api-key=admin-test-key",
        "app.new-api.catalog-sync-on-startup=false"
})
class LlmModelAdminServiceTest {

    @Autowired
    private LlmModelAdminService modelAdminService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private NewApiCatalogSyncService catalogSync;

    @Test
    void syncSuspendsTheReadOnlyAdminTransaction() {
        NewApiCatalogSyncResponse expected = new NewApiCatalogSyncResponse(
                "NEW_API",
                Instant.parse("2026-10-22T00:00:00Z"),
                2,
                0,
                2,
                0,
                12,
                8,
                4,
                0,
                2,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
        when(catalogSync.sync()).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return expected;
        });

        TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
        readOnlyTransaction.setReadOnly(true);

        NewApiCatalogSyncResponse actual = readOnlyTransaction.execute(
                status -> modelAdminService.sync());

        assertSame(expected, actual);
        verify(catalogSync).sync();
    }
}
