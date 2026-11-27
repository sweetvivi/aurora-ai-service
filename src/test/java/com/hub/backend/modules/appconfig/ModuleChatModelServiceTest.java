package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.InvalidRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.appconfig.dto.ModuleChatModelsRequest;
import com.hub.backend.modules.appconfig.dto.ModuleChatModelsResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleChatModelServiceTest {

    private static final Long SITE_ID = 3L;

    private final AppSiteSettingRepository settings = mock(AppSiteSettingRepository.class);
    private final LlmModelRepository models = mock(LlmModelRepository.class);
    private final ModelUseResolver resolver = mock(ModelUseResolver.class);
    private final ModuleChatModelService service = new ModuleChatModelService(settings, models, resolver);
    private final AppSiteEntity site = site();

    @Test
    void storesConfiguredModuleKeysAndClearsNullValues() {
        when(settings.findBySiteIdAndSettingKey(SITE_ID, "model.chat.module.super-agent"))
                .thenReturn(Optional.empty());
        when(settings.findBySiteIdAndSettingKey(SITE_ID, "model.chat.module.ai-education"))
                .thenReturn(Optional.of(new AppSiteSettingEntity(
                        SITE_ID, "model.chat.module.ai-education", "old", AppSiteSettingEntity.TYPE_STRING)));
        when(models.findVisibleByModelKey(SITE_ID, "chat-model", LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(chatModel()));
        when(settings.findBySiteIdOrderBySettingKeyAsc(SITE_ID)).thenReturn(List.of(
                new AppSiteSettingEntity(SITE_ID, "model.chat.module.super-agent", "chat-model",
                        AppSiteSettingEntity.TYPE_STRING)));

        var response = service.update(site, Map.of(
                "super-agent", " chat-model ", "ai-education", " "));

        verify(settings).save(any(AppSiteSettingEntity.class));
        verify(settings).delete(any(AppSiteSettingEntity.class));
        assertEquals(Map.of("super-agent", "chat-model"), response.configured());
    }

    @Test
    void serializesConfiguredMapWithFrontendFieldName() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new ModuleChatModelsResponse(Map.of("ai-education", "chat-model")));

        assertEquals("{\"configured\":{\"ai-education\":\"chat-model\"}}", json);
    }

    @Test
    void rejectsModelsNotVisibleAsActiveChatModels() {
        when(models.findVisibleByModelKey(SITE_ID, "image-model", LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of());

        assertThrows(InvalidRequestException.class,
                () -> service.update(site, Map.of("commerce", "image-model")));
        verify(settings, never()).save(any());
    }

    @Test
    void emptyModuleOverrideDelegatesToSiteDefaultResolver() {
        when(settings.findBySiteIdAndSettingKey(SITE_ID, "model.chat.module.commerce"))
                .thenReturn(Optional.empty());
        List<ModelUseResolver.Resolved> result = List.of(new ModelUseResolver.Resolved(
                4L, "site-default", "upstream", LlmModelEntity.TYPE_CHAT, true,
                false, null, false, null, null));
        when(resolver.resolveAllForSite(SITE_ID, null, LlmModelEntity.TYPE_CHAT)).thenReturn(result);

        assertEquals(result, service.resolveAllForSite(SITE_ID, ModuleChatModelService.COMMERCE));
        verify(resolver).resolveAllForSite(SITE_ID, null, LlmModelEntity.TYPE_CHAT);
    }

    @Test
    void rejectsUnknownModuleKeys() {
        assertThrows(InvalidRequestException.class,
                () -> service.update(site, Map.of("ordinary-agent", "chat-model")));
        verify(settings, never()).save(any());
    }

    private static AppSiteEntity site() {
        AppSiteEntity site = new AppSiteEntity("test", "Test");
        try {
            var field = AppSiteEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(site, SITE_ID);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return site;
    }

    private static LlmModelEntity chatModel() {
        LlmModelEntity model = new LlmModelEntity("chat-model", "chat-model", "Test", "chat-model");
        model.setModelType(LlmModelEntity.TYPE_CHAT);
        model.setStatus(LlmModelEntity.STATUS_ACTIVE);
        return model;
    }
}
