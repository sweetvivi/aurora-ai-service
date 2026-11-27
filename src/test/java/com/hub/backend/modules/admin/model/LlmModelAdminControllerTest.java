package com.hub.backend.modules.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.model.NewApiCatalogSyncService;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class LlmModelAdminControllerTest {

    private static final String MODELS_URL = "/api/admin/v1/models";
    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmProviderRepository providerRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private LlmModelGroupRepository groupRepository;

    @Autowired
    private LlmModelFallbackGroupRepository fallbackGroupRepository;

    @Autowired
    private LlmModelFallbackMemberRepository fallbackMemberRepository;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @MockitoBean
    private NewApiCatalogSyncService catalogSync;

    /**
     * 公共目录按 {@code app-name} 解析站点。默认站点原本由 LocalDataInitializer 在上下文启动时
     * 播种，但同一 JVM 里其它测试类（如 SiteDefaultModelAdminControllerTest）是**非事务**的，
     * 会 deleteAll 掉 app_sites 并提交；上下文被缓存复用时播种器不会再跑，播种逻辑又因为
     * 「表里已有别的站点」直接返回，于是这条用例只能拿到 404。
     *
     * <p>这里自己把站点补上：用例只依赖自己造的数据，不再依赖测试类之间的执行顺序。</p>
     */
    @BeforeEach
    void ensureDefaultSite() {
        if (appSiteRepository.findBySiteKey("default").isEmpty()) {
            appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        }
    }

    @Test
    void listsOnlyNewApiModelsAndIncludesUnavailableEntries() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-model-list");
        LlmModelEntity chat = newApiModel(
                provider, "catalog-chat", "Catalog Chat", LlmModelEntity.TYPE_CHAT);
        chat.setUpstreamAvailable(true);
        modelRepository.save(chat);

        LlmModelEntity unavailable = newApiModel(
                provider, "catalog-offline", "Offline", LlmModelEntity.TYPE_IMAGE);
        unavailable.setUpstreamAvailable(false);
        unavailable.setStatus("DISABLED");
        modelRepository.save(unavailable);

        LlmModelEntity local = localModel("catalog-local-only");
        modelRepository.save(local);

        mockMvc.perform(get(MODELS_URL).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].modelKey", hasItem("catalog-chat")))
                .andExpect(jsonPath("$[*].modelKey", hasItem("catalog-offline")))
                .andExpect(jsonPath("$[*].modelKey", not(hasItem("catalog-local-only"))))
                .andExpect(jsonPath(
                        "$[?(@.modelKey=='catalog-chat')].source",
                        hasItem("NEW_API")))
                .andExpect(jsonPath(
                        "$[?(@.modelKey=='catalog-chat')].providerKey",
                        hasItem(provider.getProviderKey())));
    }

    @Test
    void filtersByTypeStatusAndProvider() throws Exception {
        LlmProviderEntity first = provider("newapi-provider-filter-a");
        LlmProviderEntity second = provider("newapi-provider-filter-b");
        modelRepository.save(newApiModel(
                first, "filter-chat", "Filter Chat", LlmModelEntity.TYPE_CHAT));
        LlmModelEntity image = newApiModel(
                second, "filter-image", "Filter Image", LlmModelEntity.TYPE_IMAGE);
        image.setStatus("DISABLED");
        modelRepository.save(image);

        mockMvc.perform(get(MODELS_URL)
                        .param("type", "IMAGE")
                        .param("status", "disabled")
                        .param("providerId", second.getId().toString())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].modelKey", hasItem("filter-image")))
                .andExpect(jsonPath("$[*].modelKey", not(hasItem("filter-chat"))))
                .andExpect(jsonPath("$[*].id", not(hasItem(first.getId().intValue()))));
    }

    @Test
    void exposesDetailsButNeverFallsBackToLegacyLocalRows() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-detail");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "detail-model", "Detail Model", LlmModelEntity.TYPE_CHAT));
        LlmModelEntity local = modelRepository.save(localModel("detail-local"));

        mockMvc.perform(get(MODELS_URL + "/" + model.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelKey").value("detail-model"))
                .andExpect(jsonPath("$.externalId").value("detail-model"))
                .andExpect(jsonPath("$.route").value("detail-model"));

        mockMvc.perform(get(MODELS_URL + "/" + local.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void changesOnlyLocalVisibilityAndDisplayFields() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-edit");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "editable-model", "Original", LlmModelEntity.TYPE_CHAT));
        model.setDescription("Original description");
        model.setSortOrder(9);
        modelRepository.saveAndFlush(model);

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/visibility")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.route").value("editable-model"));

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelName":"Display Name",
                                  "company":"Display Vendor",
                                  "iconUrl":"https://assets.example/model.svg",
                                  "description":"Updated description",
                                  "sortOrder":3,
                                  "thinking":true,
                                  "thinkingParameter":"enable_thinking",
                                  "thinkingOnValue":"true",
                                  "thinkingOffValue":"false",
                                  "route":"must-not-change"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("Display Name"))
                .andExpect(jsonPath("$.company").value("Display Vendor"))
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.sortOrder").value(3))
                .andExpect(jsonPath("$.thinking").value(true))
                .andExpect(jsonPath("$.thinkingParameter").value("enable_thinking"))
                .andExpect(jsonPath("$.thinkingOnValue").value("true"))
                .andExpect(jsonPath("$.thinkingOffValue").value("false"))
                .andExpect(jsonPath("$.route").value("editable-model"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        LlmModelEntity saved = modelRepository.findById(model.getId()).orElseThrow();
        assertTrue(saved.isThinking());
        assertTrue(saved.getCapabilitiesJson().contains("enable_thinking"));
        assertTrue(saved.getCapabilitiesJson().contains("thinkingOnValue"));
        assertTrue(saved.getCapabilitiesJson().contains("thinkingOffValue"));
    }

    @Test
    void updatesModelTypeViaDisplayAndKeepsItAfterCorrection() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-model-type");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "type-fixable", "Type Fixable", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelType":"VIDEO"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelType").value("VIDEO"));

        assertEquals(LlmModelEntity.TYPE_VIDEO,
                modelRepository.findById(model.getId()).orElseThrow().getModelType());
    }

    @Test
    void rejectsInvalidModelTypeOnDisplay() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-model-type-invalid");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "type-invalid", "Type Invalid", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modelType":"WIDGET"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidThinkingParameterNames() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-thinking-invalid");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "thinking-invalid", "Thinking Invalid", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "thinking":true,
                                  "thinkingParameter":"enable thinking"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesVideoCapabilitiesAndPreservesThinkingParameter() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-video-caps");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "video-caps", "Video Caps", LlmModelEntity.TYPE_VIDEO));
        model.setCapabilitiesJson("{\"thinkingParameter\":\"enable_thinking\",\"modes\":[\"text2video\"]}");
        modelRepository.saveAndFlush(model);

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoCapabilities":{
                                    "modes":["text2video","image2video","first-last-frame"],
                                    "aspectRatios":["16:9","9:16","1:1"],
                                    "resolutions":["480p","720p","1080p"],
                                    "durations":[5,10,15],
                                    "maxReferenceImages":2,
                                    "audio":true
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thinkingParameter").value("enable_thinking"))
                .andExpect(jsonPath("$.capabilities.modes", hasItem("first-last-frame")))
                .andExpect(jsonPath("$.capabilities.resolutions", hasItem("1080p")))
                .andExpect(jsonPath("$.capabilities.maxReferenceImages").value(2))
                .andExpect(jsonPath("$.capabilities.audio").value(true));

        String saved = modelRepository.findById(model.getId()).orElseThrow().getCapabilitiesJson();
        assertTrue(saved.contains("enable_thinking"));
        assertTrue(saved.contains("first-last-frame"));
    }

    @Test
    void rejectsVideoCapabilitiesOnChatModels() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-video-caps-chat");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "chat-no-video-caps", "Chat", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(put(MODELS_URL + "/" + model.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "videoCapabilities":{
                                    "modes":["text2video"]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void displayAndVisibilityNeverTouchLegacyLocalRows() throws Exception {
        LlmModelEntity local = modelRepository.save(localModel("legacy-write"));

        mockMvc.perform(put(MODELS_URL + "/" + local.getId() + "/visibility")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(MODELS_URL + "/" + local.getId() + "/display")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"Changed\"}"))
                .andExpect(status().isNotFound());

        assertEquals("legacy-write", modelRepository.findById(local.getId()).orElseThrow().getModelName());
        assertEquals("ACTIVE", modelRepository.findById(local.getId()).orElseThrow().getStatus());
    }

    @Test
    void hiddenAndUnavailableModelsDisappearFromThePublicCatalogue() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-public");
        LlmModelGroupEntity group = groupRepository.save(
                new LlmModelGroupEntity(null, "catalog-public", "Catalog Public", "Test"));

        LlmModelEntity visible = newApiModel(
                provider, "public-visible", "Public Visible", LlmModelEntity.TYPE_CHAT);
        visible.setGroupId(group.getId());
        visible = modelRepository.save(visible);

        LlmModelFallbackGroupEntity version = fallbackGroupRepository.save(
                new LlmModelFallbackGroupEntity(
                        null, "public-version", "Public Version", LlmModelEntity.TYPE_CHAT));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(version.getId(), visible.getId(), 0));

        LlmModelEntity hidden = newApiModel(
                provider, "public-hidden", "Public Hidden", LlmModelEntity.TYPE_CHAT);
        hidden.setStatus("DISABLED");
        modelRepository.save(hidden);

        LlmModelEntity unavailable = newApiModel(
                provider, "public-unavailable", "Public Unavailable", LlmModelEntity.TYPE_CHAT);
        unavailable.setUpstreamAvailable(false);
        modelRepository.save(unavailable);

        mockMvc.perform(get("/api/v1/models").header("app-name", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..modelKey", hasItem("public-version")))
                .andExpect(jsonPath("$..modelKey", not(hasItem("public-visible"))))
                .andExpect(jsonPath("$..modelKey", not(hasItem("public-hidden"))))
                .andExpect(jsonPath("$..modelKey", not(hasItem("public-unavailable"))));

        mockMvc.perform(get("/api/v1/models/public-version").header("app-name", "default"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/models/public-hidden").header("app-name", "default"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/models/public-unavailable").header("app-name", "default"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchUpdatesVisibilityAndDisplayFieldsInOneRequest() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-batch-ok");
        LlmModelEntity first = modelRepository.save(newApiModel(
                provider, "batch-first", "Batch First", LlmModelEntity.TYPE_CHAT));
        LlmModelEntity second = modelRepository.save(newApiModel(
                provider, "batch-second", "Batch Second", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items":[
                                    {"modelId":%d,"status":"DISABLED","modelName":"Renamed",
                                     "company":"","thinking":true,
                                     "thinkingParameter":"enable_thinking","sortOrder":0},
                                    {"modelId":%d,"status":"ACTIVE","sortOrder":1}
                                  ]
                                }
                                """.formatted(first.getId(), second.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.models[0].id").value(first.getId()))
                .andExpect(jsonPath("$.models[0].status").value("DISABLED"))
                .andExpect(jsonPath("$.models[0].modelName").value("Renamed"))
                .andExpect(jsonPath("$.models[0].company").doesNotExist())
                .andExpect(jsonPath("$.models[0].thinking").value(true))
                .andExpect(jsonPath("$.models[0].thinkingParameter").value("enable_thinking"))
                .andExpect(jsonPath("$.models[0].sortOrder").value(0))
                .andExpect(jsonPath("$.models[1].id").value(second.getId()))
                .andExpect(jsonPath("$.models[1].sortOrder").value(1))
                // 未提交的字段保持原值，null 才是「跳过」
                .andExpect(jsonPath("$.models[1].modelName").value("Batch Second"));

        LlmModelEntity savedFirst = modelRepository.findById(first.getId()).orElseThrow();
        assertEquals("DISABLED", savedFirst.getStatus());
        assertEquals("Renamed", savedFirst.getModelName());
        assertTrue(savedFirst.isThinking());
        assertEquals("ACTIVE", modelRepository.findById(second.getId()).orElseThrow().getStatus());
    }

    @Test
    void batchUpdateRejectsInvalidPayloadsWithoutWriting() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-batch-invalid");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "batch-invalid", "Batch Invalid", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        StringBuilder tooMany = new StringBuilder("{\"items\":[");
        for (int index = 0; index < 101; index += 1) {
            tooMany.append(index == 0 ? "" : ",")
                    .append("{\"modelId\":").append(model.getId()).append('}');
        }
        tooMany.append("]}");
        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooMany.toString()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"modelId":%d},{"modelId":%d}]}
                                """.formatted(model.getId(), model.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("duplicated modelId")));

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"modelId\":%d,\"status\":\"HIDDEN\"}]}"
                                .formatted(model.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"modelId\":%d,\"sortOrder\":-1}]}"
                                .formatted(model.getId())))
                .andExpect(status().isBadRequest());

        assertEquals("ACTIVE", modelRepository.findById(model.getId()).orElseThrow().getStatus());
    }

    @Test
    void batchUpdateRollsBackEverythingWhenOneModelIsMissing() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-batch-404");
        LlmModelEntity present = modelRepository.save(newApiModel(
                provider, "batch-present", "Batch Present", LlmModelEntity.TYPE_CHAT));
        LlmModelEntity local = modelRepository.save(localModel("batch-local"));

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"modelId":%d,"status":"DISABLED","modelName":"Should Not Stick"},
                                  {"modelId":99999999,"status":"DISABLED"}
                                ]}
                                """.formatted(present.getId())))
                .andExpect(status().isNotFound());

        LlmModelEntity untouched = modelRepository.findById(present.getId()).orElseThrow();
        assertEquals("ACTIVE", untouched.getStatus());
        assertEquals("Batch Present", untouched.getModelName());
    }

    @Test
    void batchUpdateRefusesLegacyLocalRows() throws Exception {
        LlmModelEntity local = modelRepository.save(localModel("batch-legacy"));

        mockMvc.perform(put(MODELS_URL + "/batch")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"modelId\":%d,\"status\":\"DISABLED\"}]}"
                                .formatted(local.getId())))
                .andExpect(status().isNotFound());

        assertEquals("ACTIVE", modelRepository.findById(local.getId()).orElseThrow().getStatus());
    }

    @Test
    void protectsBatchUpdateWithTheAdminKey() throws Exception {
        mockMvc.perform(put(MODELS_URL + "/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"modelId\":1}]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removesLegacyManualCrudRoutes() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-crud");
        LlmModelEntity model = modelRepository.save(newApiModel(
                provider, "crud-model", "CRUD Model", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(post(MODELS_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelKey\":\"manual\"}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(MODELS_URL + "/" + model.getId())
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"Manual update\"}"))
                .andExpect(status().isMethodNotAllowed());
        // DELETE 现在是「清理已下线模型」，不是手工 CRUD：仍在上游提供的模型必须被拒绝。
        mockMvc.perform(delete(MODELS_URL + "/" + model.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isConflict());
        assertTrue(modelRepository.findById(model.getId()).isPresent());
    }

    @Test
    void deletesRetiredModelsAndDetachesTheirReferences() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-purge");
        LlmModelEntity retired = newApiModel(
                provider, "purge-retired", "Purge Retired", LlmModelEntity.TYPE_CHAT);
        retired.setUpstreamAvailable(false);
        retired = modelRepository.save(retired);
        Long retiredId = retired.getId();

        LlmModelFallbackGroupEntity version = fallbackGroupRepository.save(
                new LlmModelFallbackGroupEntity(
                        null, "purge-version", "Purge Version", LlmModelEntity.TYPE_CHAT));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(version.getId(), retiredId, 0));

        mockMvc.perform(delete(MODELS_URL + "/" + retiredId)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        assertTrue(modelRepository.findById(retiredId).isEmpty());
        // 成员关系随模型一起消失，否则外键会让删除失败
        assertTrue(fallbackMemberRepository
                .findByModelIdOrderByGroupIdAscPriorityAscIdAsc(retiredId).isEmpty());
    }

    @Test
    void refusesToDeleteModelsStillServedUpstream() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-purge-live");
        LlmModelEntity live = modelRepository.save(newApiModel(
                provider, "purge-live", "Purge Live", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(delete(MODELS_URL + "/" + live.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("仍在上游提供")));

        assertTrue(modelRepository.findById(live.getId()).isPresent());
    }

    @Test
    void batchDeletePurgesRetiredModelsAndSkipsTheRest() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-batch-purge");
        LlmModelEntity retired = newApiModel(
                provider, "batch-purge-retired", "Retired", LlmModelEntity.TYPE_CHAT);
        retired.setUpstreamAvailable(false);
        retired = modelRepository.save(retired);
        LlmModelEntity live = modelRepository.save(newApiModel(
                provider, "batch-purge-live", "Live", LlmModelEntity.TYPE_CHAT));

        mockMvc.perform(post(MODELS_URL + "/batch-delete")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[%d,%d,%d]}"
                                .formatted(retired.getId(), live.getId(), retired.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.skipped[0].modelId").value(live.getId()))
                .andExpect(jsonPath("$.skipped[0].reason").value(
                        org.hamcrest.Matchers.containsString("仍在上游提供")));

        assertTrue(modelRepository.findById(retired.getId()).isEmpty());
        assertTrue(modelRepository.findById(live.getId()).isPresent());

        // 重复提交是幂等的：全都进 skipped，不再报 404
        mockMvc.perform(post(MODELS_URL + "/batch-delete")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[%d]}".formatted(retired.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value("模型已不存在"));
    }

    @Test
    void batchDeleteRejectsEmptyAndOversizedPayloads() throws Exception {
        mockMvc.perform(post(MODELS_URL + "/batch-delete")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[]}"))
                .andExpect(status().isBadRequest());

        StringBuilder tooMany = new StringBuilder("{\"modelIds\":[");
        for (int index = 0; index <= 100; index += 1) {
            tooMany.append(index == 0 ? "" : ",").append(index + 1);
        }
        tooMany.append("]}");
        mockMvc.perform(post(MODELS_URL + "/batch-delete")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooMany.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersByUpstreamAvailability() throws Exception {
        LlmProviderEntity provider = provider("newapi-provider-availability");
        modelRepository.save(newApiModel(
                provider, "availability-live", "Live", LlmModelEntity.TYPE_CHAT));
        LlmModelEntity retired = newApiModel(
                provider, "availability-retired", "Retired", LlmModelEntity.TYPE_CHAT);
        retired.setUpstreamAvailable(false);
        modelRepository.save(retired);

        mockMvc.perform(get(MODELS_URL)
                        .param("availability", "OFFLINE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].modelKey", hasItem("availability-retired")))
                .andExpect(jsonPath("$[*].modelKey", not(hasItem("availability-live"))));

        mockMvc.perform(get(MODELS_URL)
                        .param("availability", "ONLINE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].modelKey", hasItem("availability-live")))
                .andExpect(jsonPath("$[*].modelKey", not(hasItem("availability-retired"))));
    }

    @Test
    void protectsCatalogSyncAndRunsWithoutManagementToken() throws Exception {
        when(catalogSync.sync()).thenReturn(new NewApiCatalogSyncResponse(
                "NEW_API", Instant.parse("2026-10-31T00:00:00Z"),
                1, 1, 0, 0,
                2, 2, 0, 0,
                0, 0, 0, 0,
                2, 0, 0, 0, 0));

        mockMvc.perform(get(MODELS_URL))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(MODELS_URL + "/sync"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(MODELS_URL + "/sync").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("NEW_API"))
                .andExpect(jsonPath("$.providersTotal").value(1))
                .andExpect(jsonPath("$.modelsTotal").value(2));
    }

    @Test
    void publishesOnlyTheNewAdminSurfaceInOpenApi() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var paths = objectMapper.readTree(body).path("paths");

        assertTrue(paths.has("/api/admin/v1/models"));
        assertTrue(paths.has("/api/admin/v1/models/batch"));
        assertTrue(paths.has("/api/admin/v1/models/batch-delete"));
        assertTrue(paths.has("/api/admin/v1/models/{id}"));
        assertTrue(paths.has("/api/admin/v1/models/{id}/visibility"));
        assertTrue(paths.has("/api/admin/v1/models/{id}/display"));
        assertTrue(paths.has("/api/admin/v1/models/sync"));
        assertFalse(paths.has("/api/admin/v1/providers/{id}/models"));
    }

    private LlmProviderEntity provider(String key) {
        LlmProviderEntity provider = new LlmProviderEntity(
                key,
                key,
                "OPENAI_COMPATIBLE",
                "https://gateway.example/v1",
                null,
                null,
                "BEARER",
                "config:new-api-user-relay",
                "ACTIVE",
                0);
        provider.setSource(LlmProviderEntity.SOURCE_NEW_API);
        provider.setExternalId(key);
        provider.setUpstreamAvailable(true);
        provider.setProviderGroup("default");
        return providerRepository.save(provider);
    }

    private LlmModelEntity newApiModel(LlmProviderEntity provider,
                                       String key,
                                       String name,
                                       String type) {
        LlmModelEntity model = new LlmModelEntity(key, name, "Test Vendor", key);
        model.setSource(LlmModelEntity.SOURCE_NEW_API);
        model.setExternalId(key);
        model.setProviderId(provider.getId());
        model.setModelType(type);
        model.setUpstreamAvailable(true);
        return model;
    }

    private LlmModelEntity localModel(String key) {
        LlmModelEntity model = new LlmModelEntity(key, key, "Legacy", key);
        model.setModelType(LlmModelEntity.TYPE_CHAT);
        return model;
    }
}
