package com.hub.backend.modules.model;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.price.ModelPriceService;
import com.hub.backend.modules.model.price.ModelPriceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelFallbackCatalogServiceTest {

    @Mock
    private LlmModelFallbackGroupRepository groups;

    @Mock
    private LlmModelFallbackMemberRepository members;

    @Mock
    private LlmModelRepository models;

    @Mock
    private ModelPriceService modelPrices;

    private ModelFallbackCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelFallbackCatalogService(groups, members, models, modelPrices);
    }

    @Test
    void ordersByEffectivePriceAndFiltersInvalidMembers() {
        LlmModelFallbackGroupEntity group = group(
                10L, null, "gpt-image-2.0", "GPT-Image-2.0",
                LlmModelEntity.TYPE_IMAGE, false);
        LlmModelEntity expensive = model(
                1L, null, "expensive", "expensive-route", LlmModelEntity.TYPE_IMAGE, 8, 1);
        LlmModelEntity tiered = model(
                2L, null, "tiered", "tiered-route", LlmModelEntity.TYPE_IMAGE, 0, 2);
        tiered.setInputPointsPer1k(new BigDecimal("1.5"));
        tiered.setOutputPointsPer1k(new BigDecimal("2.5"));
        LlmModelEntity cheap = model(
                3L, null, "cheap", "cheap-route", LlmModelEntity.TYPE_IMAGE, 2, 3);
        LlmModelEntity disabled = model(
                4L, null, "disabled", "disabled-route", LlmModelEntity.TYPE_IMAGE, 1, 4);
        disabled.setStatus("DISABLED");
        LlmModelEntity unavailable = model(
                5L, null, "unavailable", "unavailable-route",
                LlmModelEntity.TYPE_IMAGE, 1, 5);
        unavailable.setUpstreamAvailable(false);
        LlmModelEntity wrongType = model(
                6L, null, "chat", "chat-route", LlmModelEntity.TYPE_CHAT, 1, 6);
        LlmModelEntity unpriced = model(
                7L, null, "unpriced", "unpriced-route", LlmModelEntity.TYPE_IMAGE, 0, 7);

        when(groups.findVisibleByGroupKeyForResolution(7L, "gpt-image-2.0"))
                .thenReturn(List.of(group));
        when(members.findByGroupIdOrderByPriorityAscIdAsc(10L)).thenReturn(List.of(
                member(10L, expensive.getId(), 0),
                member(10L, disabled.getId(), 1),
                member(10L, tiered.getId(), 2),
                member(10L, unavailable.getId(), 3),
                member(10L, cheap.getId(), 4),
                member(10L, wrongType.getId(), 5),
                member(10L, unpriced.getId(), 6)));
        when(models.findAllById(anyList())).thenReturn(List.of(
                expensive, tiered, cheap, disabled, unavailable, wrongType, unpriced));
        when(modelPrices.priceOf("expensive")).thenReturn(price("8"));
        when(modelPrices.priceOf("tiered")).thenReturn(price("0.15"));
        when(modelPrices.priceOf("cheap")).thenReturn(price("2"));
        when(modelPrices.priceOf("unpriced")).thenReturn(ModelPriceView.unpriced());

        ModelFallbackCatalogService.GroupView view = service
                .findVisibleGroup(7L, "gpt-image-2.0", LlmModelEntity.TYPE_IMAGE)
                .orElseThrow();

        assertEquals(
                List.of("tiered", "cheap", "expensive", "unpriced"),
                view.candidates().stream()
                        .map(candidate -> candidate.model().getModelKey())
                        .toList());
    }

    @Test
    void usesCustomPriorityWhenTheGroupOrderWasEdited() {
        LlmModelFallbackGroupEntity group = group(
                11L, null, "chat-version", "Chat Version", LlmModelEntity.TYPE_CHAT, true);
        LlmModelEntity cheap = model(
                21L, null, "cheap", "cheap-route", LlmModelEntity.TYPE_CHAT, 1, 1);
        LlmModelEntity expensive = model(
                22L, null, "expensive", "expensive-route", LlmModelEntity.TYPE_CHAT, 9, 2);

        when(groups.findVisibleByGroupKeyForResolution(7L, "chat-version"))
                .thenReturn(List.of(group));
        when(members.findByGroupIdOrderByPriorityAscIdAsc(11L)).thenReturn(List.of(
                member(11L, cheap.getId(), 1),
                member(11L, expensive.getId(), 0)));
        when(models.findAllById(anyList())).thenReturn(List.of(cheap, expensive));

        ModelFallbackCatalogService.GroupView view = service
                .findVisibleGroup(7L, "chat-version", LlmModelEntity.TYPE_CHAT)
                .orElseThrow();

        assertEquals(
                List.of("expensive", "cheap"),
                view.candidates().stream()
                        .map(candidate -> candidate.model().getModelKey())
                        .toList());
    }

    @Test
    void deduplicatesMembersThatResolveToTheSameUpstreamRoute() {
        LlmModelFallbackGroupEntity group = group(
                12L, null, "chat-version", "Chat Version", LlmModelEntity.TYPE_CHAT, false);
        LlmModelEntity expensiveDuplicate = model(
                31L, null, "alias-a", "same-route", LlmModelEntity.TYPE_CHAT, 8, 1);
        LlmModelEntity cheapDuplicate = model(
                32L, null, "alias-b", " SAME-ROUTE ", LlmModelEntity.TYPE_CHAT, 2, 2);
        LlmModelEntity distinct = model(
                33L, null, "alias-c", "other-route", LlmModelEntity.TYPE_CHAT, 4, 3);

        when(groups.findVisibleByGroupKeyForResolution(7L, "chat-version"))
                .thenReturn(List.of(group));
        when(members.findByGroupIdOrderByPriorityAscIdAsc(12L)).thenReturn(List.of(
                member(12L, expensiveDuplicate.getId(), 0),
                member(12L, cheapDuplicate.getId(), 1),
                member(12L, distinct.getId(), 2)));
        when(models.findAllById(anyList())).thenReturn(
                List.of(expensiveDuplicate, cheapDuplicate, distinct));
        when(modelPrices.priceOf("alias-a")).thenReturn(price("8"));
        when(modelPrices.priceOf("alias-b")).thenReturn(price("2"));
        when(modelPrices.priceOf("alias-c")).thenReturn(price("4"));

        ModelFallbackCatalogService.GroupView view = service
                .findVisibleGroup(7L, "chat-version", LlmModelEntity.TYPE_CHAT)
                .orElseThrow();

        assertEquals(
                List.of("alias-b", "alias-c"),
                view.candidates().stream()
                        .map(candidate -> candidate.model().getModelKey())
                        .toList());
    }

    @Test
    void sameKeySiteGroupReplacesTheGlobalGroup() {
        LlmModelFallbackGroupEntity global = group(
                13L, null, "chat-version", "Global Version", LlmModelEntity.TYPE_CHAT, false);
        LlmModelFallbackGroupEntity site = group(
                14L, 7L, "chat-version", "Site Version", LlmModelEntity.TYPE_CHAT, false);
        LlmModelEntity globalModel = model(
                41L, null, "global-model", "global-route", LlmModelEntity.TYPE_CHAT, 2, 1);
        LlmModelEntity siteModel = model(
                42L, 7L, "site-model", "site-route", LlmModelEntity.TYPE_CHAT, 1, 2);

        when(groups.findVisible(7L, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(global, site));
        when(members.findByGroupIdInOrderByPriorityAscIdAsc(List.of(14L)))
                .thenReturn(List.of(member(14L, siteModel.getId(), 0)));
        when(models.findAllById(anyList())).thenReturn(List.of(siteModel));

        List<ModelFallbackCatalogService.GroupView> visible = service.visibleGroups(7L, null);

        assertEquals(1, visible.size());
        assertEquals(14L, visible.get(0).group().getId());
        assertEquals(
                List.of("site-model"),
                visible.get(0).candidates().stream()
                        .map(candidate -> candidate.model().getModelKey())
                        .toList());
    }

    @Test
    void ordersVisibleGroupsByLowestAnonymousPriceAndPutsUnpricedLast() {
        LlmModelFallbackGroupEntity expensiveGroup = group(
                17L, null, "expensive-version", "Expensive Version",
                LlmModelEntity.TYPE_CHAT, false);
        expensiveGroup.setSortOrder(0);
        LlmModelFallbackGroupEntity cheapGroup = group(
                18L, null, "cheap-version", "Cheap Version",
                LlmModelEntity.TYPE_CHAT, false);
        cheapGroup.setSortOrder(50);
        LlmModelFallbackGroupEntity unpricedGroup = group(
                19L, null, "unpriced-version", "Unpriced Version",
                LlmModelEntity.TYPE_CHAT, false);
        unpricedGroup.setSortOrder(1);
        LlmModelEntity expensive = model(
                61L, null, "expensive", "expensive-route", LlmModelEntity.TYPE_CHAT, 8, 1);
        LlmModelEntity cheap = model(
                62L, null, "cheap", "cheap-route", LlmModelEntity.TYPE_CHAT, 2, 2);
        LlmModelEntity unpriced = model(
                63L, null, "unpriced", "unpriced-route", LlmModelEntity.TYPE_CHAT, 0, 3);

        when(groups.findVisibleByType(
                7L, LlmModelEntity.TYPE_CHAT, LlmModelEntity.STATUS_ACTIVE))
                .thenReturn(List.of(unpricedGroup, expensiveGroup, cheapGroup));
        when(members.findByGroupIdInOrderByPriorityAscIdAsc(List.of(19L, 17L, 18L)))
                .thenReturn(List.of(
                        member(19L, unpriced.getId(), 0),
                        member(17L, expensive.getId(), 0),
                        member(18L, cheap.getId(), 0)));
        when(models.findAllById(anyList())).thenReturn(List.of(unpriced, expensive, cheap));
        when(modelPrices.priceOf("expensive")).thenReturn(price("8"));
        when(modelPrices.priceOf("cheap")).thenReturn(price("2"));
        when(modelPrices.priceOf("unpriced")).thenReturn(ModelPriceView.unpriced());

        List<ModelFallbackCatalogService.GroupView> visible =
                service.visibleGroups(7L, LlmModelEntity.TYPE_CHAT);

        assertEquals(
                List.of("cheap-version", "expensive-version", "unpriced-version"),
                visible.stream().map(view -> view.group().getGroupKey()).toList());
    }

    @Test
    void rejectsVisibleGroupWithNoEligibleMembers() {
        LlmModelFallbackGroupEntity group = group(
                15L, null, "empty-version", "Empty Version", LlmModelEntity.TYPE_IMAGE, false);
        LlmModelEntity disabled = model(
                51L, null, "disabled", "disabled-route", LlmModelEntity.TYPE_IMAGE, 1, 1);
        disabled.setStatus("DISABLED");

        when(groups.findVisibleByGroupKeyForResolution(7L, "empty-version"))
                .thenReturn(List.of(group));
        when(members.findByGroupIdOrderByPriorityAscIdAsc(15L))
                .thenReturn(List.of(member(15L, disabled.getId(), 0)));
        when(models.findAllById(anyList())).thenReturn(List.of(disabled));

        assertThrows(
                NotFoundException.class,
                () -> service.findVisibleGroup(7L, "empty-version", LlmModelEntity.TYPE_IMAGE));
    }

    @Test
    void rejectsAGroupUsedAsTheWrongModelType() {
        LlmModelFallbackGroupEntity group = group(
                16L, null, "image-version", "Image Version", LlmModelEntity.TYPE_IMAGE, false);
        when(groups.findVisibleByGroupKeyForResolution(7L, "image-version"))
                .thenReturn(List.of(group));

        assertThrows(
                InvalidRequestException.class,
                () -> service.findVisibleGroup(7L, "image-version", LlmModelEntity.TYPE_CHAT));
    }

    private static LlmModelFallbackGroupEntity group(
            Long id,
            Long siteId,
            String groupKey,
            String displayName,
            String modelType,
            boolean customOrder) {
        LlmModelFallbackGroupEntity group =
                new LlmModelFallbackGroupEntity(siteId, groupKey, displayName, modelType);
        group.setOrderCustomized(customOrder);
        ReflectionTestUtils.setField(group, "id", id);
        return group;
    }

    private static LlmModelFallbackMemberEntity member(
            Long groupId,
            Long modelId,
            int priority) {
        return new LlmModelFallbackMemberEntity(groupId, modelId, priority);
    }

    private static ModelPriceView price(String value) {
        return new ModelPriceView(
                ModelPriceView.MODE_TOKEN,
                new BigDecimal(value),
                null,
                null,
                new BigDecimal(value));
    }

    private static LlmModelEntity model(
            Long id,
            Long siteId,
            String key,
            String route,
            String type,
            int effectivePrice,
            int sortOrder) {
        LlmModelEntity model = new LlmModelEntity(key, key, "Vendor", route);
        model.setSiteId(siteId);
        model.setModelType(type);
        model.setConsumePoints(BigDecimal.valueOf(effectivePrice));
        model.setSortOrder(sortOrder);
        ReflectionTestUtils.setField(model, "id", id);
        return model;
    }
}
