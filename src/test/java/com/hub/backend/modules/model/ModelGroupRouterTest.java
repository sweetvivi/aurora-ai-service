package com.hub.backend.modules.model;

import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.NewApiGroupEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.NewApiGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分组路由的三条规则：默认分组优先、目录里不可用的分组被跳过、没有分组信息时不猜。
 */
@SpringBootTest(properties = {
        "app.new-api.group=default",
        "app.new-api.base-url=https://new-api.example",
        "app.new-api.catalog-sync-on-startup=false"
})
@Transactional
class ModelGroupRouterTest {

    @MockitoBean
    private NewApiCatalogSyncService catalogSyncService;

    @Autowired
    private ModelGroupRouter router;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private NewApiGroupRepository groups;

    @Test
    void prefersTheConfiguredGroupWhenTheModelIsAvailableThere() {
        useGroup("default", true);
        useGroup("vip", true);
        LlmModelEntity model = model("gpt-5", "vip,default");

        assertEquals(List.of("default", "vip"), router.candidateGroups(model.getId(), "default"));
        assertEquals("default", router.preferredGroup(model.getId(), "default"));
    }

    @Test
    void fallsBackToTheModelsOwnGroupWhenTheDefaultDoesNotServeIt() {
        useGroup("default", true);
        useGroup("图片生成", true);
        LlmModelEntity model = model("gpt-image-2", "图片生成");

        assertEquals(List.of("图片生成"), router.candidateGroups(model.getId(), "default"));
        assertEquals("图片生成", router.preferredGroup(model.getId(), "default"));
    }

    @Test
    void skipsGroupsTheCatalogueMarkedUnusable() {
        useGroup("default", true);
        useGroup("vip", false);
        useGroup("kimi", true);
        LlmModelEntity model = model("kimi-k2.5", "vip,kimi");

        assertEquals(List.of("kimi"), router.candidateGroups(model.getId(), "default"));
    }

    @Test
    void keepsTheDeclaredOrderWhenTheCatalogueHasNoGroupsYet() {
        // 分组表还没同步：不能凭一份空表把模型判死，按模型自述的顺序交给上游。
        LlmModelEntity model = model("glm-5.3", "default,vip");

        assertEquals(List.of("default", "vip"), router.candidateGroups(model.getId(), "default"));
    }

    @Test
    void returnsNoGroupForAModelWithoutGroupsOrForAMissingId() {
        LlmModelEntity model = model("legacy-model", null);

        assertTrue(router.candidateGroups(model.getId(), "default").isEmpty());
        assertNull(router.preferredGroup(model.getId(), "default"));
        assertTrue(router.candidateGroups(null, "default").isEmpty());
        assertTrue(router.candidateGroups(-1L, "default").isEmpty());
    }

    @Test
    void borrowsGroupsFromASiblingRowWithTheSameModelKey() {
        // 历史手工建的行不会有 upstream_groups；同步行才有。目录里选中老行时不能直接掉到 default。
        useGroup("default", true);
        useGroup("图片生成", true);
        LlmModelEntity legacy = model("gpt-image-2", null);
        LlmModelEntity oldLocalRow = legacy;
        oldLocalRow.setSource(LlmModelEntity.SOURCE_LOCAL);
        oldLocalRow.setExternalId("local-gpt-image-2");
        models.saveAndFlush(oldLocalRow);
        model("gpt-image-2", "图片生成");

        assertEquals(List.of("图片生成"), router.candidateGroups(legacy.getId(), "default"));
        assertEquals("图片生成", router.preferredGroup(legacy.getId(), "default"));
    }

    @Test
    void ignoresTheReservedAutoNameAsAPreference() {
        useGroup("default", true);
        useGroup("vip", true);
        LlmModelEntity model = model("gpt-5", "default,vip");

        assertEquals("default", router.preferredGroup(model.getId(), "auto"));
    }

    private void useGroup(String key, boolean usable) {
        NewApiGroupEntity group = groups.findByGroupKey(key)
                .orElseGet(() -> new NewApiGroupEntity(
                        key, null, BigDecimal.ONE, LocalDateTime.now()));
        group.refresh(null, BigDecimal.ONE, usable, LocalDateTime.now());
        groups.saveAndFlush(group);
    }

    private LlmModelEntity model(String key, String upstreamGroups) {
        LlmModelEntity model = new LlmModelEntity(key, key, "test", key);
        model.setSource(LlmModelEntity.SOURCE_NEW_API);
        model.setExternalId(key);
        model.setModelType(LlmModelEntity.TYPE_CHAT);
        model.setStatus(LlmModelEntity.STATUS_ACTIVE);
        model.setUpstreamAvailable(true);
        model.setUpstreamGroups(upstreamGroups);
        return models.saveAndFlush(model);
    }
}
