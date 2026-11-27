package com.hub.backend.modules.model;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.model.entity.LlmModelBrandEntity;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelFallbackMemberEntity;
import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import com.hub.backend.modules.model.entity.LlmModelTagEntity;
import com.hub.backend.modules.model.repository.LlmModelBrandRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelFallbackMemberRepository;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.model.repository.LlmModelTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModelCatalogControllerTest {

    private static final String CATALOG_URL = "/api/v1/models";

    /** Deliberately different from the model key: it must never reach a client. */
    private static final String UPSTREAM_ROUTE = "upstream-secret-route-xyz";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private LlmModelGroupRepository groupRepository;

    @Autowired
    private LlmModelTagRepository tagRepository;

    @Autowired
    private LlmModelFallbackGroupRepository fallbackGroupRepository;

    @Autowired
    private LlmModelFallbackMemberRepository fallbackMemberRepository;

    @Autowired
    private LlmModelBrandRepository brandRepository;

    private Long siteId;

    @BeforeEach
    void seedCatalog() {
        fallbackMemberRepository.deleteAll();
        fallbackGroupRepository.deleteAll();
        tagRepository.deleteAll();
        modelRepository.deleteAll();
        brandRepository.deleteAll();
        groupRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        siteId = site.getId();

        LlmModelGroupEntity globalGroup = groupRepository.save(
                new LlmModelGroupEntity(null, "demo-relay", "示例中转", "Jiguang"));
        groupRepository.save(new LlmModelGroupEntity(null, "empty-group", "空分组", "Nobody"));

        LlmModelEntity global = new LlmModelEntity(
                "deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek", UPSTREAM_ROUTE);
        global.setGroupId(globalGroup.getId());
        global.setSortOrder(10);
        global.setThinking(true);
        global.setCapabilitiesJson("{\"thinkingParameter\":\"enable_thinking\"}");
        global.setPointsAllowed(true);
        global.setDescription("通用对话模型");
        global = modelRepository.saveAndFlush(global);
        assertNotNull(global.getCapabilitiesJson(), "capabilities_json must round-trip on H2");
        assertTrue(global.getCapabilitiesJson().contains("enable_thinking"));
        tagRepository.save(new LlmModelTagEntity(global.getId(), "思考", 10));
        tagRepository.save(new LlmModelTagEntity(global.getId(), "长文本", 20));

        // site specific row, visible to this site only
        LlmModelEntity siteModel = modelRepository.save(
                new LlmModelEntity("local-model", "Local Model", "Local", "local-upstream"));
        siteModel.setSiteId(siteId);
        siteModel.setGroupId(globalGroup.getId());
        siteModel.setSortOrder(20);
        siteModel.setFree(true);
        modelRepository.save(siteModel);

        // no group at all
        LlmModelEntity ungrouped = modelRepository.save(
                new LlmModelEntity("ungrouped-model", "Ungrouped", "Nobody", "ungrouped-upstream"));
        ungrouped.setSortOrder(30);
        modelRepository.save(ungrouped);

        // visible, but no vendor recorded: must still surface, under the "other" family
        LlmModelEntity unbranded = modelRepository.save(
                new LlmModelEntity("unbranded-model", "Unbranded", null, "unbranded-upstream"));
        unbranded.setSortOrder(40);
        modelRepository.save(unbranded);

        // retired model
        LlmModelEntity disabled = modelRepository.save(
                new LlmModelEntity("retired-model", "Retired", "Nobody", "retired-upstream"));
        disabled.setStatus("DISABLED");
        modelRepository.save(disabled);

        // belongs to another white label site
        LlmModelEntity foreign = modelRepository.save(
                new LlmModelEntity("foreign-model", "Foreign", "Nobody", "foreign-upstream"));
        foreign.setSiteId(siteId + 999);
        modelRepository.save(foreign);

        LlmModelFallbackGroupEntity globalChatVersion = fallbackGroupRepository.save(
                fallbackGroup("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "CHAT", null, 10));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(globalChatVersion.getId(), global.getId(), 0));

        // A site group with the same public key replaces the global group and may add a
        // same-site implementation from another original model family.
        LlmModelFallbackGroupEntity siteChatVersion = fallbackGroupRepository.save(
                fallbackGroup("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "CHAT", siteId, 10));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(siteChatVersion.getId(), global.getId(), 0));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(siteChatVersion.getId(), siteModel.getId(), 1));

        LlmModelFallbackGroupEntity unbrandedVersion = fallbackGroupRepository.save(
                fallbackGroup("unbranded-version", "Unbranded Version", "CHAT", null, 20));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(unbrandedVersion.getId(), unbranded.getId(), 0));
    }

    @Test
    void listsGlobalAndOwnModelsButNotTheirs() throws Exception {
        mockMvc.perform(get(CATALOG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalModels").value(2))
                .andExpect(jsonPath("$.models", hasSize(2)))
                // display order follows the version-group sort order
                .andExpect(jsonPath("$.models[0].modelKey").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.models[0].modelName").value("DeepSeek V4.1 Flash"))
                .andExpect(jsonPath("$.models[1].modelKey").value("unbranded-version"))
                .andExpect(jsonPath("$..modelKey").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("local-model"))));
    }

    @Test
    void hidesRetiredModelsAndOtherSitesModels() throws Exception {
        mockMvc.perform(get(CATALOG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[?(@.modelKey=='retired-model')]").doesNotExist())
                .andExpect(jsonPath("$.models[?(@.modelKey=='foreign-model')]").doesNotExist());
    }

    @Test
    void neverExposesTheUpstreamRoute() throws Exception {
        String catalog = mockMvc.perform(get(CATALOG_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String detail = mockMvc.perform(get(CATALOG_URL + "/deepseek-v4.1-flash"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(catalog.contains(UPSTREAM_ROUTE), "catalogue leaked the upstream route");
        assertFalse(catalog.contains("route"), "catalogue must not carry a route field at all");
        assertFalse(detail.contains(UPSTREAM_ROUTE), "detail leaked the upstream route");
    }

    @Test
    void returnsDisplayFieldsAndTagsInOrder() throws Exception {
        mockMvc.perform(get(CATALOG_URL + "/deepseek-v4.1-flash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelKey").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.modelName").value("DeepSeek V4.1 Flash"))
                .andExpect(jsonPath("$.company").value("DeepSeek"))
                .andExpect(jsonPath("$.groupKey").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.description").value("通用对话模型"))
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.tags[0]").value("思考"))
                .andExpect(jsonPath("$.tags[1]").value("长文本"))
                .andExpect(jsonPath("$.thinking").value(true))
                .andExpect(jsonPath("$.capabilities.thinkingParameter").value("enable_thinking"))
                .andExpect(jsonPath("$.pointsAllowed").value(true))
                .andExpect(jsonPath("$.consumePoints").value(0));
    }

    @Test
    void reportsModelCountPerGroupAndDropsEmptyGroups() throws Exception {
        mockMvc.perform(get(CATALOG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].groupKey").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.groups[0].label").value("DeepSeek V4.1 Flash"))
                .andExpect(jsonPath("$.groups[0].company").value("DeepSeek"))
                .andExpect(jsonPath("$.groups[0].modelCount").value(1))
                .andExpect(jsonPath("$.groups[1].groupKey").value("unbranded-version"))
                .andExpect(jsonPath("$.groups[1].label").value("Unbranded Version"));
    }

    @Test
    void hidesModelsWithoutAVersionGroup() throws Exception {
        String body = mockMvc.perform(get(CATALOG_URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("ungrouped-model"),
                "a model without a version group belongs to the hidden other bucket");
    }

    @Test
    void filtersByGroup() throws Exception {
        mockMvc.perform(get(CATALOG_URL).param("group", "deepseek-v4.1-flash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalModels").value(1))
                .andExpect(jsonPath("$.models[0].modelKey").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.groups", hasSize(1)))
                .andExpect(jsonPath("$.groups[0].groupKey").value("deepseek-v4.1-flash"));

        mockMvc.perform(get(CATALOG_URL).param("group", "does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalModels").value(0))
                .andExpect(jsonPath("$.groups", hasSize(0)));
    }

    @Test
    void hidesAModelThatIsNotVisibleToThisSite() throws Exception {
        mockMvc.perform(get(CATALOG_URL + "/retired-model"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(CATALOG_URL + "/foreign-model"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(CATALOG_URL + "/ungrouped-model"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(CATALOG_URL + "/unknown-model"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("内容不存在")));
    }

    @Test
    void servesTheCatalogueOfTheRequestedSite() throws Exception {
        appSiteRepository.save(new AppSiteEntity("other-site", "Other Site"));

        // the second site sees the global rows only, not the first site's local model
        mockMvc.perform(get(CATALOG_URL).header("app-name", "other-site"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalModels").value(2))
                .andExpect(jsonPath("$.models[?(@.modelKey=='local-model')]").doesNotExist());

        mockMvc.perform(get(CATALOG_URL + "/local-model").header("app-name", "other-site"))
                .andExpect(status().isNotFound());
    }

    @Test
    void foldsVisibleModelsIntoFamiliesByVendor() throws Exception {
        mockMvc.perform(get(CATALOG_URL + "/families"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFamilies").value(2))
                .andExpect(jsonPath("$.totalModels").value(2))
                .andExpect(jsonPath("$.families[0].familyKey").value("deepseek"))
                .andExpect(jsonPath("$.families[0].displayName").value("DeepSeek"))
                .andExpect(jsonPath("$.families[0].configured").value(false))
                .andExpect(jsonPath("$.families[0].iconUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.families[0].modelCount").value(1))
                .andExpect(jsonPath("$.families[0].models", hasSize(1)))
                // families carry the same safe entries as the catalogue, and never the upstream route
                .andExpect(jsonPath("$.families[0].models[0].modelKey").value("deepseek-v4.1-flash"));
    }

    @Test
    void decoratesFamiliesWithConfiguredBrandIconAndSort() throws Exception {
        LlmModelBrandEntity brand = new LlmModelBrandEntity("deepseek", "深度求索");
        brand.setIconUrl("/media/commerce/deepseek.png");
        brand.setDescription("开源对话");
        brand.setSortOrder(1);
        brandRepository.save(brand);

        // CJK display_name match: company "可灵" → brand_key kling
        LlmModelEntity video = modelRepository.save(
                new LlmModelEntity("kling-v1", "Kling V1", "可灵", "kling-v1"));
        video.setModelType(LlmModelEntity.TYPE_VIDEO);
        video.setSortOrder(5);
        video = modelRepository.save(video);
        LlmModelFallbackGroupEntity videoVersion = fallbackGroupRepository.save(
                fallbackGroup("kling-v1", "Kling V1", "VIDEO", null, 5));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(videoVersion.getId(), video.getId(), 0));

        LlmModelBrandEntity kling = new LlmModelBrandEntity("kling", "可灵");
        kling.setIconUrl("/media/commerce/kling.png");
        kling.setSortOrder(0);
        brandRepository.save(kling);

        mockMvc.perform(get(CATALOG_URL + "/families").param("type", "VIDEO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFamilies").value(1))
                .andExpect(jsonPath("$.families[0].familyKey").value("kling"))
                .andExpect(jsonPath("$.families[0].displayName").value("可灵"))
                .andExpect(jsonPath("$.families[0].iconUrl").value("/media/commerce/kling.png"))
                .andExpect(jsonPath("$.families[0].configured").value(true))
                .andExpect(jsonPath("$.families[0].sortOrder").value(0))
                .andExpect(jsonPath("$.families[0].models[0].modelKey").value("kling-v1"));

        // configured brand sorts ahead of unconfigured "other"
        mockMvc.perform(get(CATALOG_URL + "/families").param("type", "CHAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.families[0].familyKey").value("deepseek"))
                .andExpect(jsonPath("$.families[0].displayName").value("深度求索"))
                .andExpect(jsonPath("$.families[0].iconUrl").value("/media/commerce/deepseek.png"))
                .andExpect(jsonPath("$.families[0].description").value("开源对话"))
                .andExpect(jsonPath("$.families[0].configured").value(true))
                .andExpect(jsonPath("$.families[1].familyKey").value("other"))
                .andExpect(jsonPath("$.families[1].configured").value(false));
    }

    @Test
    void filtersFamiliesByModelType() throws Exception {
        LlmModelEntity image = modelRepository.save(
                new LlmModelEntity("gpt-image-2", "GPT Image 2", "OpenAI", "gpt-image-2"));
        image.setModelType(LlmModelEntity.TYPE_IMAGE);
        image.setSortOrder(50);
        image = modelRepository.save(image);
        LlmModelFallbackGroupEntity imageVersion = fallbackGroupRepository.save(
                fallbackGroup("gpt-image-2.0", "GPT-Image-2.0", "IMAGE", null, 30));
        fallbackMemberRepository.save(
                new LlmModelFallbackMemberEntity(imageVersion.getId(), image.getId(), 0));

        // a chat picker and a drawing panel are built from families; neither may offer the other's
        // models, which is what a type-less family view used to do
        mockMvc.perform(get(CATALOG_URL + "/families").param("type", "IMAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalModels").value(1))
                .andExpect(jsonPath("$.totalFamilies").value(1))
                .andExpect(jsonPath("$.families[0].familyKey").value("openai"))
                .andExpect(jsonPath("$.families[0].modelCount").value(1))
                .andExpect(jsonPath("$.families[0].models[0].modelKey").value("gpt-image-2.0"))
                .andExpect(jsonPath("$.families[0].models[0].modelName").value("GPT-Image-2.0"));

        String chatOnly = mockMvc.perform(get(CATALOG_URL + "/families").param("type", "CHAT"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(chatOnly.contains("gpt-image-2"), "the chat view must not offer an image model");

        // a type matching nothing is an empty view, not an error - same rule as the catalogue
        mockMvc.perform(get(CATALOG_URL + "/families").param("type", "AUDIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalModels").value(0))
                .andExpect(jsonPath("$.families", hasSize(0)));
    }

    @Test
    void putsModelsWithoutAVendorIntoAnOtherFamily() throws Exception {
        mockMvc.perform(get(CATALOG_URL + "/families"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.families[?(@.familyKey=='other')].displayName").value(
                        org.hamcrest.Matchers.contains("其他")))
                .andExpect(jsonPath("$.families[?(@.familyKey=='other')].models[0].modelKey")
                        .value("unbranded-version"));
    }

    @Test
    void hidesRetiredAndForeignModelsFromFamilies() throws Exception {
        String body = mockMvc.perform(get(CATALOG_URL + "/families"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("retired-model"));
        assertFalse(body.contains("foreign-model"));
        assertFalse(body.contains("ungrouped-model"));
        assertFalse(body.contains(UPSTREAM_ROUTE), "families must not expose the upstream route");
    }

    private static LlmModelFallbackGroupEntity fallbackGroup(
            String key,
            String displayName,
            String modelType,
            Long siteId,
            int sortOrder) {
        LlmModelFallbackGroupEntity group =
                new LlmModelFallbackGroupEntity(siteId, key, displayName, modelType);
        group.setSortOrder(sortOrder);
        return group;
    }

    @Test
    void publishesCatalogPathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/models'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/models/families'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/models/{modelKey}'].get").exists())
                .andExpect(jsonPath("$.components.schemas.ModelCatalog").exists())
                .andExpect(jsonPath("$.components.schemas.ModelSummary").exists())
                .andExpect(jsonPath("$.components.schemas.ModelGroup").exists())
                .andExpect(jsonPath("$.components.schemas.ModelFamily").exists());
    }
}
