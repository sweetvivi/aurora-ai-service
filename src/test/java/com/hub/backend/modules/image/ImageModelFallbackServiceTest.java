package com.hub.backend.modules.image;

import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.modules.model.ModelUseResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageModelFallbackServiceTest {

    @Mock
    private ModelUseResolver modelResolver;

    private ImageModelFallbackService service;

    @BeforeEach
    void setUp() {
        service = new ImageModelFallbackService(modelResolver);
    }

    @Test
    void retriesOnceWithTheConfiguredModelWhenTheGatewayHasNotPricedTheRequestedModel() {
        ModelUseResolver.Resolved primary =
                new ModelUseResolver.Resolved(1L, "gpt-image-2.5", "gpt-image-2.5", "IMAGE", true,
                        false, null, false, null, null);
        ModelUseResolver.Resolved fallback =
                new ModelUseResolver.Resolved(2L, "gpt-image-2", "gpt-image-2", "IMAGE", true,
                        false, null, false, null, null);
        when(modelResolver.resolveAllForSite(
                7L, primary.requestKey(), LlmModelEntity.TYPE_IMAGE))
                .thenReturn(List.of(primary));
        when(modelResolver.resolveAllForSite(7L, null, LlmModelEntity.TYPE_IMAGE))
                .thenReturn(List.of(fallback));
        List<String> attemptedModels = new ArrayList<>();

        ImageModelFallbackService.InvocationResult<String> result = service.invoke(
                7L,
                primary,
                selected -> {
                    attemptedModels.add(selected.upstreamKey());
                    if (selected == primary) {
                        throw new LlmInvocationException(
                                "Image gateway returned HTTP 400: "
                                        + "{\"code\":\"model_price_error\","
                                        + "\"message\":\"Model gpt-image-2.5 has not been priced\"}");
                    }
                    return "generated";
                });

        assertEquals(List.of("gpt-image-2.5", "gpt-image-2"), attemptedModels);
        assertEquals("generated", result.value());
        assertEquals("gpt-image-2", result.model().requestKey());
        assertTrue(result.fallbackUsed());
    }

    @Test
    void doesNotRetryUnrelatedGatewayErrors() {
        ModelUseResolver.Resolved primary =
                new ModelUseResolver.Resolved(1L, "gpt-image-2.5", "gpt-image-2.5", "IMAGE", true,
                        false, null, false, null, null);
        LlmInvocationException failure =
                new LlmInvocationException("Image gateway returned HTTP 500: upstream unavailable");
        int[] calls = {0};

        LlmInvocationException thrown = assertThrows(
                LlmInvocationException.class,
                () -> service.invoke(7L, primary, selected -> {
                    calls[0]++;
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(1, calls[0]);
        verify(modelResolver, never())
                .resolveAllForSite(7L, null, LlmModelEntity.TYPE_IMAGE);
    }

    @Test
    void doesNotRetryWhenTheFallbackResolvesToTheSameUpstreamRoute() {
        ModelUseResolver.Resolved primary =
                new ModelUseResolver.Resolved(1L, "gpt-image-2.5", "gpt-image-2.5", "IMAGE", true,
                        false, null, false, null, null);
        ModelUseResolver.Resolved fallback =
                new ModelUseResolver.Resolved(1L, "gpt-image-2", "gpt-image-2.5", "IMAGE", true,
                        false, null, false, null, null);
        when(modelResolver.resolveAllForSite(
                7L, primary.requestKey(), LlmModelEntity.TYPE_IMAGE))
                .thenReturn(List.of(primary));
        when(modelResolver.resolveAllForSite(7L, null, LlmModelEntity.TYPE_IMAGE))
                .thenReturn(List.of(fallback));
        LlmInvocationException failure =
                new LlmInvocationException("model_price_error: model has not been priced");
        int[] calls = {0};

        LlmInvocationException thrown = assertThrows(
                LlmInvocationException.class,
                () -> service.invoke(7L, primary, selected -> {
                    calls[0]++;
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(1, calls[0], "the same upstream model must not be sent twice");
    }
}
