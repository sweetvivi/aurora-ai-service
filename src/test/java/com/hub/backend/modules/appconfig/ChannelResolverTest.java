package com.hub.backend.modules.appconfig;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelResolverTest {

    private static final AppSiteEntity DEFAULT_SITE = new AppSiteEntity("default", "Default Site");
    private static final AppSiteEntity SHOP_SITE = new AppSiteEntity("shop", "Shop Site");

    @Mock
    private AppSiteRepository repository;

    private AppProperties appProperties;
    private ChannelResolver resolver;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        resolver = new ChannelResolver(repository, appProperties);
    }

    @Test
    void explicitSiteHeaderWinsOverTheHost() {
        when(repository.findBySiteKey("shop")).thenReturn(Optional.of(SHOP_SITE));

        AppSiteEntity resolved = resolver.resolve("shop", "default.example.com");

        assertEquals("shop", resolved.getSiteKey());
    }

    @Test
    void unknownExplicitSiteIsRejectedInsteadOfFallingBack() {
        when(repository.findBySiteKey("ghost")).thenReturn(Optional.empty());

        NotFoundException exception = assertThrows(NotFoundException.class,
                () -> resolver.resolve("ghost", "default.example.com"));

        assertTrue(exception.getMessage().contains("ghost"));
    }

    @Test
    void blankHeaderFallsThroughToTheHostMapping() {
        when(repository.findFirstByDomainIgnoreCase("shop.example.com")).thenReturn(Optional.of(SHOP_SITE));

        assertEquals("shop", resolver.resolve("   ", "shop.example.com").getSiteKey());
        assertEquals("shop", resolver.resolve(null, "shop.example.com").getSiteKey());
    }

    @Test
    void unknownHostFallsBackToTheConfiguredDefaultSite() {
        when(repository.findFirstByDomainIgnoreCase("unknown.example.com")).thenReturn(Optional.empty());
        when(repository.findBySiteKey("default")).thenReturn(Optional.of(DEFAULT_SITE));

        assertEquals("default", resolver.resolve(null, "unknown.example.com").getSiteKey());
    }

    @Test
    void domainFallbackCanBeDisabled() {
        appProperties.setDomainFallback(false);
        when(repository.findBySiteKey("default")).thenReturn(Optional.of(DEFAULT_SITE));

        assertEquals("default", resolver.resolve(null, "shop.example.com").getSiteKey());
    }

    @Test
    void missingDefaultSiteIsReportedAsNotFound() {
        when(repository.findBySiteKey("default")).thenReturn(Optional.empty());

        NotFoundException exception = assertThrows(NotFoundException.class,
                () -> resolver.resolve(null, null));

        assertTrue(exception.getMessage().contains("default"));
    }
}
