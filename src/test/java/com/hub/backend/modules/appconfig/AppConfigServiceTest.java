package com.hub.backend.modules.appconfig;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.modules.appconfig.dto.SiteBrandingLogos;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigServiceTest {

    private final AppSiteSettingRepository settingRepository =
            mock(AppSiteSettingRepository.class);
    private final LlmModelRepository modelRepository = mock(LlmModelRepository.class);
    private final NavigationSettingsLayoutCodec navigationLayoutCodec =
            mock(NavigationSettingsLayoutCodec.class);
    private final SiteBrandingService siteBrandingService = mock(SiteBrandingService.class);
    private final AppSiteEntity site = mock(AppSiteEntity.class);
    private final LlmProperties llmProperties = new LlmProperties();
    private AppConfigService service;

    @BeforeEach
    void setUp() {
        when(site.getId()).thenReturn(1L);
        when(site.getSiteKey()).thenReturn("default");
        when(site.getDisplayName()).thenReturn("Default Site");
        when(site.getStatus()).thenReturn("ACTIVE");
        when(settingRepository.findBySiteIdOrderBySettingKeyAsc(1L)).thenReturn(List.of());
        when(siteBrandingService.readLogos(1L)).thenReturn(SiteBrandingLogos.empty());
        service = new AppConfigService(
                settingRepository,
                new AppProperties(),
                llmProperties,
                navigationLayoutCodec,
                new ModelUseResolver(modelRepository,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        settingRepository,
                        llmProperties),
                siteBrandingService);
    }

    @Test
    void keepsTheConfiguredDefaultWhenItIsStillAvailable() {
        llmProperties.setDefaultModel("deepseek-v4-1-flash-261017");
        when(modelRepository.findVisible(1L, LlmModelEntity.STATUS_ACTIVE)).thenReturn(List.of(
                chatModel("deepseek-v4-1-flash-261017"),
                chatModel("deepseek-v4-1-flash-260910")));

        assertThat(service.loadConfig(site).defaultModel())
                .isEqualTo("deepseek-v4-1-flash-261017");
    }

    @Test
    void switchesToTheFirstVisibleChatModelWhenTheConfiguredDefaultWasRetired() {
        llmProperties.setDefaultModel("deepseek-v4.1-flash");
        when(modelRepository.findVisible(1L, LlmModelEntity.STATUS_ACTIVE)).thenReturn(List.of(
                imageModel("gpt-image-2.5"),
                chatModel("deepseek-v4-1-flash-261017")));

        assertThat(service.loadConfig(site).defaultModel())
                .isEqualTo("deepseek-v4-1-flash-261017");
    }

    @Test
    void keepsTheConfiguredDefaultBeforeTheCatalogueHasLoaded() {
        llmProperties.setDefaultModel("deepseek-v4.1-flash");
        when(modelRepository.findVisible(1L, LlmModelEntity.STATUS_ACTIVE)).thenReturn(List.of());

        assertThat(service.loadConfig(site).defaultModel()).isEqualTo("deepseek-v4.1-flash");
    }

    @Test
    void exposesTheEffectiveDatabaseDefaultToRuntimeCallers() {
        AppSiteSettingEntity setting = mock(AppSiteSettingEntity.class);
        when(setting.getSettingKey()).thenReturn(AppSiteSettingEntity.KEY_DEFAULT_MODEL);
        when(setting.getSettingValue()).thenReturn("deepseek-v4-1-flash-260910");
        when(settingRepository.findBySiteIdOrderBySettingKeyAsc(1L)).thenReturn(List.of(setting));
        when(modelRepository.findVisible(1L, LlmModelEntity.STATUS_ACTIVE)).thenReturn(List.of(
                chatModel("deepseek-v4-1-flash-260910")));

        assertThat(service.defaultModelForSite(1L))
                .isEqualTo("deepseek-v4-1-flash-260910");
    }

    private static LlmModelEntity chatModel(String modelKey) {
        LlmModelEntity model = new LlmModelEntity(modelKey, modelKey, "DeepSeek", modelKey);
        model.setModelType(LlmModelEntity.TYPE_CHAT);
        return model;
    }

    private static LlmModelEntity imageModel(String modelKey) {
        LlmModelEntity model = new LlmModelEntity(modelKey, modelKey, "OpenAI", modelKey);
        model.setModelType(LlmModelEntity.TYPE_IMAGE);
        return model;
    }
}
