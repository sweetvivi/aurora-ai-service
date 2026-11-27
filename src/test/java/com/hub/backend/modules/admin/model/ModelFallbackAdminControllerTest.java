package com.hub.backend.modules.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=admin-test-key",
        "app.new-api.catalog-sync-on-startup=false"
})
@AutoConfigureMockMvc
@Transactional
class ModelFallbackAdminControllerTest {

    private static final String URL = "/api/admin/v1/model-fallbacks";
    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository sites;

    @Autowired
    private LlmModelRepository models;

    private AppSiteEntity site;
    private AppSiteEntity otherSite;

    @BeforeEach
    void setUpSites() {
        String suffix = UUID.randomUUID().toString();
        site = sites.save(new AppSiteEntity("fallback-site-" + suffix, "Fallback Site"));
        otherSite = sites.save(new AppSiteEntity("fallback-other-" + suffix, "Other Site"));
    }

    @Test
    void addsCrossFamilyMembersAndSupportsCustomThenPriceOrder() throws Exception {
        LlmModelEntity familyA = model(
                "fallback-image-a-" + UUID.randomUUID(),
                "Vendor A",
                LlmModelEntity.TYPE_IMAGE,
                30,
                2);
        LlmModelEntity familyB = model(
                "fallback-image-b-" + UUID.randomUUID(),
                "Vendor B",
                LlmModelEntity.TYPE_IMAGE,
                10,
                1);
        long groupId = createGroup(null, "gpt-image-2.0", "GPT-Image-2.0", "IMAGE");

        addMember(groupId, familyA.getId())
                .andExpect(status().isOk());
        addMember(groupId, familyB.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderingMode").value("PRICE_ASC"))
                .andExpect(jsonPath("$.members[0].modelId").value(familyB.getId()))
                .andExpect(jsonPath("$.members[1].modelId").value(familyA.getId()));

        mockMvc.perform(put(URL + "/" + groupId + "/members/order")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelIds":[%d,%d]}
                                """.formatted(familyA.getId(), familyB.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCustomized").value(true))
                .andExpect(jsonPath("$.orderingMode").value("CUSTOM"))
                .andExpect(jsonPath("$.members[0].modelId").value(familyA.getId()))
                .andExpect(jsonPath("$.members[1].modelId").value(familyB.getId()));

        mockMvc.perform(put(URL + "/" + groupId + "/order/price")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCustomized").value(false))
                .andExpect(jsonPath("$.orderingMode").value("PRICE_ASC"))
                .andExpect(jsonPath("$.members[0].modelId").value(familyB.getId()))
                .andExpect(jsonPath("$.members[1].modelId").value(familyA.getId()));
    }

    @Test
    void rejectsScopeAndTypeMismatches() throws Exception {
        LlmModelEntity siteImage = siteModel(
                site.getId(), "fallback-site-image-" + UUID.randomUUID(),
                LlmModelEntity.TYPE_IMAGE);
        LlmModelEntity otherSiteImage = siteModel(
                otherSite.getId(), "fallback-other-image-" + UUID.randomUUID(),
                LlmModelEntity.TYPE_IMAGE);
        LlmModelEntity globalChat = model(
                "fallback-global-chat-" + UUID.randomUUID(),
                "Chat Vendor",
                LlmModelEntity.TYPE_CHAT,
                1,
                1);
        long globalGroup = createGroup(null, "global-version", "Global Version", "IMAGE");
        long siteGroup = createGroup(site.getId(), "site-version", "Site Version", "IMAGE");

        addMember(globalGroup, siteImage.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("global group")));
        addMember(globalGroup, globalChat.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("type")));
        addMember(siteGroup, otherSiteImage.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("same site")));
    }

    @Test
    void candidateListUsesTypeSiteStatusAndAvailabilityFilters() throws Exception {
        LlmModelEntity global = model(
                "fallback-candidate-global-" + UUID.randomUUID(),
                "Global Vendor",
                LlmModelEntity.TYPE_IMAGE,
                3,
                1);
        LlmModelEntity local = siteModel(
                site.getId(), "fallback-candidate-local-" + UUID.randomUUID(),
                LlmModelEntity.TYPE_IMAGE);
        LlmModelEntity otherLocal = siteModel(
                otherSite.getId(), "fallback-candidate-other-" + UUID.randomUUID(),
                LlmModelEntity.TYPE_IMAGE);
        LlmModelEntity unavailable = model(
                "fallback-candidate-unavailable-" + UUID.randomUUID(),
                "Unavailable Vendor",
                LlmModelEntity.TYPE_IMAGE,
                1,
                2);
        unavailable.setUpstreamAvailable(false);
        models.saveAndFlush(unavailable);
        LlmModelEntity chat = model(
                "fallback-candidate-chat-" + UUID.randomUUID(),
                "Chat Vendor",
                LlmModelEntity.TYPE_CHAT,
                1,
                3);

        mockMvc.perform(get(URL + "/candidates")
                        .param("type", "IMAGE")
                        .param("siteId", site.getId().toString())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].modelId", hasItem(global.getId().intValue())))
                .andExpect(jsonPath("$[*].modelId", hasItem(local.getId().intValue())))
                .andExpect(jsonPath("$[*].modelId", not(hasItem(otherLocal.getId().intValue()))))
                .andExpect(jsonPath("$[*].modelId", not(hasItem(unavailable.getId().intValue()))))
                .andExpect(jsonPath("$[*].modelId", not(hasItem(chat.getId().intValue()))));
    }

    @Test
    void candidateListExposesSyncedUpstreamGroups() throws Exception {
        LlmModelEntity synced = model(
                "fallback-candidate-synced-" + UUID.randomUUID(),
                "Synced Vendor",
                LlmModelEntity.TYPE_IMAGE,
                1,
                1);
        synced.setUpstreamGroups("default, vip");
        models.saveAndFlush(synced);

        LlmModelEntity noGroups = model(
                "fallback-candidate-nogroups-" + UUID.randomUUID(),
                "No Group Vendor",
                LlmModelEntity.TYPE_IMAGE,
                1,
                2);

        // 同步行带分组 → 按 upstream_groups 原样返回；没同步到的行 → 空数组而不是 null，
        // 前端据此区分「没分组」和「接口没给这个字段」。
        mockMvc.perform(get(URL + "/candidates")
                        .param("type", "IMAGE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.modelId == %d)].upstreamGroups[*]",
                        synced.getId().intValue()).value(
                        org.hamcrest.Matchers.contains("default", "vip")))
                .andExpect(jsonPath(
                        "$[?(@.modelId == %d)].upstreamGroups",
                        noGroups.getId().intValue()).value(
                        org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.empty())));
    }

    @Test
    void candidateBorrowsGroupsFromSiblingRowWithSameKey() throws Exception {
        // 老的手工行（source=LOCAL）永远拿不到同步写入的分组；界面不能因此显示「没有分组」，
        // 否则运营以为它走默认分组，实际请求走的是兄弟行声明的 vip。
        String sharedKey = "fallback-shared-" + UUID.randomUUID();
        LlmModelEntity syncedSibling = new LlmModelEntity(sharedKey, sharedKey, "Vendor", sharedKey);
        syncedSibling.setModelType(LlmModelEntity.TYPE_IMAGE);
        syncedSibling.setUpstreamGroups("vip");
        syncedSibling.setSortOrder(1);
        models.saveAndFlush(syncedSibling);

        LlmModelEntity localRow = new LlmModelEntity(sharedKey, sharedKey, "Vendor", sharedKey);
        localRow.setModelType(LlmModelEntity.TYPE_IMAGE);
        localRow.setSortOrder(2);
        models.saveAndFlush(localRow);

        mockMvc.perform(get(URL + "/candidates")
                        .param("type", "IMAGE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.modelId == %d)].upstreamGroups[*]",
                        localRow.getId().intValue()).value(
                        org.hamcrest.Matchers.contains("vip")));
    }

    @Test
    void batchEnableSkipsInvalidMembersAndCountsUnchanged() throws Exception {
        LlmModelEntity healthy = model(
                "fallback-batch-good-" + UUID.randomUUID(),
                "Good Vendor",
                LlmModelEntity.TYPE_IMAGE,
                5,
                1);
        LlmModelEntity retired = model(
                "fallback-batch-retired-" + UUID.randomUUID(),
                "Retired Vendor",
                LlmModelEntity.TYPE_IMAGE,
                6,
                2);
        long groupId = createGroup(null, "batch-enabled-group", "Batch Enabled", "IMAGE");
        addMember(groupId, healthy.getId()).andExpect(status().isOk());
        addMember(groupId, retired.getId()).andExpect(status().isOk());
        retired.setUpstreamAvailable(false);
        models.saveAndFlush(retired);

        // 全部先停用：两条都真的变了。
        mockMvc.perform(put(URL + "/" + groupId + "/members/batch-enabled")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelIds":[%d,%d],"enabled":false}
                                """.formatted(healthy.getId(), retired.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.skipped").isEmpty());

        // 重复同一次停用：幂等，全部计入 unchanged。
        mockMvc.perform(put(URL + "/" + groupId + "/members/batch-enabled")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelIds":[%d,%d],"enabled":false}
                                """.formatted(healthy.getId(), retired.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.unchanged").value(2))
                .andExpect(jsonPath("$.skipped").isEmpty());

        // 再启用：上游已下线的那条进 skipped，重复 id 被去重，非成员也进 skipped。
        mockMvc.perform(put(URL + "/" + groupId + "/members/batch-enabled")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelIds":[%d,%d,%d,%d],"enabled":true}
                                """.formatted(
                                        healthy.getId(),
                                        retired.getId(),
                                        healthy.getId(),
                                        healthy.getId() + 100000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(4))
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.unchanged").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].modelId").value(retired.getId()))
                .andExpect(jsonPath("$.skipped[0].reason").value("upstream is unavailable"))
                .andExpect(jsonPath("$.skipped[1].reason").value("not a member of this group"))
                .andExpect(jsonPath("$.group.id").value(groupId));
    }

    @Test
    void batchRemoveIsIdempotentAndReindexesPrioritiesOnce() throws Exception {
        long groupId = createGroup(null, "batch-remove-group", "Batch Remove", "IMAGE");
        LlmModelEntity first = model(
                "fallback-remove-a-" + UUID.randomUUID(), "A", LlmModelEntity.TYPE_IMAGE, 1, 1);
        LlmModelEntity second = model(
                "fallback-remove-b-" + UUID.randomUUID(), "B", LlmModelEntity.TYPE_IMAGE, 2, 2);
        LlmModelEntity third = model(
                "fallback-remove-c-" + UUID.randomUUID(), "C", LlmModelEntity.TYPE_IMAGE, 3, 3);
        addMember(groupId, first.getId()).andExpect(status().isOk());
        addMember(groupId, second.getId()).andExpect(status().isOk());
        addMember(groupId, third.getId()).andExpect(status().isOk());

        mockMvc.perform(post(URL + "/" + groupId + "/members/batch-remove")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[%d,%d]}"
                                .formatted(first.getId(), second.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.skipped").isEmpty())
                .andExpect(jsonPath("$.group.memberCount").value(1));

        mockMvc.perform(get(URL + "/" + groupId).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].modelId").value(third.getId()))
                .andExpect(jsonPath("$.members[0].priority").value(0));

        // 重复提交：全部 skipped，成员数不再变化。
        mockMvc.perform(post(URL + "/" + groupId + "/members/batch-remove")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[%d,%d]}"
                                .formatted(first.getId(), second.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.group.memberCount").value(1));
    }

    @Test
    void batchMemberEndpointsValidateTheirPayloadAndGroup() throws Exception {
        long groupId = createGroup(null, "batch-validate-group", "Batch Validate", "IMAGE");

        mockMvc.perform(put(URL + "/" + groupId + "/members/batch-enabled")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[],\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(URL + "/" + groupId + "/members/batch-enabled")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[1]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(URL + "/" + groupId + "/members/batch-remove")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(URL + "/99999999/members/batch-enabled")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[1],\"enabled\":true}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(URL + "/99999999/members/batch-remove")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[1]}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(URL + "/" + groupId + "/members/batch-enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[1],\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectsTheAdminSurfaceAndPublishesItsOpenApiPaths() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());

        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var paths = objectMapper.readTree(body).path("paths");

        assertTrue(paths.has(URL));
        assertTrue(paths.has(URL + "/{id}"));
        assertTrue(paths.has(URL + "/{id}/members"));
        assertTrue(paths.has(URL + "/{id}/members/{modelId}"));
        assertTrue(paths.has(URL + "/{id}/members/{modelId}/enabled"));
        assertTrue(paths.has(URL + "/{id}/members/batch-enabled"));
        assertTrue(paths.has(URL + "/{id}/members/batch-remove"));
        assertTrue(paths.has(URL + "/{id}/members/order"));
        assertTrue(paths.has(URL + "/{id}/order/price"));
        assertTrue(paths.has(URL + "/candidates"));
    }

    @Test
    void videoGroupAcceptsDurationOverrideAndRejectsItElsewhere() throws Exception {
        long videoGroup = createGroup(null, "seedance-2.0-override", "Seedance 2.0", "VIDEO");

        // 组里保留只认 10/15 的成员，同时对前台开放 4–15 —— 这是运营覆盖存在的唯一理由。
        mockMvc.perform(put(URL + "/" + videoGroup)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupKey":"seedance-2.0-override",
                                  "displayName":"Seedance 2.0",
                                  "modelType":"VIDEO",
                                  "durationMode":"range",
                                  "durations":[4,15]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMode").value("range"))
                .andExpect(jsonPath("$.durations", org.hamcrest.Matchers.contains(4, 15)));

        // 区间只能给两个端点，给多了是实现错误而不是「更精确」。
        mockMvc.perform(put(URL + "/" + videoGroup)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupKey":"seedance-2.0-override",
                                  "displayName":"Seedance 2.0",
                                  "modelType":"VIDEO",
                                  "durationMode":"range",
                                  "durations":[4,8,15]
                                }
                                """))
                .andExpect(status().isBadRequest());

        // 只给一半（缺 durations）也应拒绝，否则库里会留下一个说不清含义的 mode。
        mockMvc.perform(put(URL + "/" + videoGroup)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupKey":"seedance-2.0-override",
                                  "displayName":"Seedance 2.0",
                                  "modelType":"VIDEO",
                                  "durationMode":"set"
                                }
                                """))
                .andExpect(status().isBadRequest());

        long imageGroup = createGroup(null, "gpt-image-override", "GPT-Image", "IMAGE");
        mockMvc.perform(put(URL + "/" + imageGroup)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupKey":"gpt-image-override",
                                  "displayName":"GPT-Image",
                                  "modelType":"IMAGE",
                                  "durationMode":"set",
                                  "durations":[10,15]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private long createGroup(
            Long siteId,
            String groupKey,
            String displayName,
            String modelType) throws Exception {
        String siteValue = siteId == null ? "null" : siteId.toString();
        String response = mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "siteId":%s,
                                  "groupKey":"%s",
                                  "displayName":"%s",
                                  "modelType":"%s",
                                  "status":"ACTIVE",
                                  "sortOrder":10
                                }
                                """.formatted(siteValue, groupKey, displayName, modelType)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupKey").value(groupKey))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions addMember(
            long groupId,
            long modelId) throws Exception {
        return mockMvc.perform(post(URL + "/" + groupId + "/members")
                .header("X-Admin-Key", ADMIN_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":%d,"enabled":true}
                        """.formatted(modelId)));
    }

    private LlmModelEntity model(
            String key,
            String company,
            String type,
            int price,
            int sortOrder) {
        LlmModelEntity model = new LlmModelEntity(key, key, company, key);
        model.setModelType(type);
        model.setConsumePoints(java.math.BigDecimal.valueOf(price));
        model.setSortOrder(sortOrder);
        return models.saveAndFlush(model);
    }

    private LlmModelEntity siteModel(Long siteId, String key, String type) {
        LlmModelEntity model = model(key, "Site Vendor", type, 2, 1);
        model.setSiteId(siteId);
        return models.saveAndFlush(model);
    }
}
