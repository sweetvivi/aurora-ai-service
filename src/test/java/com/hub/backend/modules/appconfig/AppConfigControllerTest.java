package com.hub.backend.modules.appconfig;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.admin.api-key=admin-test-key")
@AutoConfigureMockMvc
class AppConfigControllerTest {

    private static final String CONFIG_URL = "/api/v1/app/config";
    private static final String LEGACY_INIT_URL = "/api/v2/config/init";
    private static final String EDUCATION_APPEARANCE_URL = "/api/v1/education/appearance";
    private static final String EDUCATION_APPEARANCE_ADMIN_URL = "/api/admin/v1/education-appearance";
    private static final String NAVIGATION_SETTINGS_ADMIN_URL = "/api/admin/v1/navigation-settings";
    private static final String BRANDING_ADMIN_URL = "/api/admin/v1/site-settings/branding";
    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private AppSiteSettingRepository appSiteSettingRepository;

    @BeforeEach
    void seedSites() {
        appSiteSettingRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity defaultSite = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        AppSiteEntity shopSite = new AppSiteEntity("shop", "Shop Site");
        shopSite.setDomain("shop.example.com");
        appSiteRepository.save(shopSite);

        appSiteSettingRepository.saveAll(List.of(
                new AppSiteSettingEntity(defaultSite.getId(), "feature.attachment", "true",
                        AppSiteSettingEntity.TYPE_BOOLEAN),
                new AppSiteSettingEntity(defaultSite.getId(), "feature.share", "false",
                        AppSiteSettingEntity.TYPE_BOOLEAN),
                new AppSiteSettingEntity(defaultSite.getId(), "default_model", "deepseek-v4-pro-0813",
                        AppSiteSettingEntity.TYPE_STRING),
                new AppSiteSettingEntity(defaultSite.getId(), "announcement", "系统维护中",
                        AppSiteSettingEntity.TYPE_STRING)));
    }

    @Test
    void returnsSiteConfigurationWithSettingsOverridingServerDefaults() throws Exception {
        mockMvc.perform(get(CONFIG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("default"))
                .andExpect(jsonPath("$.appName").value("Default Site"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.defaultModel").value("deepseek-v4-pro-0813"))
                .andExpect(jsonPath("$.announcement").value("系统维护中"))
                .andExpect(jsonPath("$.navigation.home").value(true))
                .andExpect(jsonPath("$.navigation.shop").value(true))
                .andExpect(jsonPath("$.navigationSections.length()").value(4))
                .andExpect(jsonPath("$.navigationSections[0].id").value("main"))
                .andExpect(jsonPath("$.navigationSections[1].title").value("新功能"))
                .andExpect(jsonPath("$.navigationSections[1].itemKeys[0]").value("voice"))
                // overridden by app_site_settings
                .andExpect(jsonPath("$.features.attachment").value(true))
                .andExpect(jsonPath("$.features.share").value(false))
                // kept from the server side defaults
                .andExpect(jsonPath("$.features.chat").value(true))
                .andExpect(jsonPath("$.features.stream").value(true))
                .andExpect(jsonPath("$.features.points").value(false))
                .andExpect(jsonPath("$.logos.default").doesNotExist())
                .andExpect(jsonPath("$.logos.auth").doesNotExist())
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void savesSiteBrandingFromAdminAndPublishesItInAppConfig() throws Exception {
        mockMvc.perform(put(BRANDING_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "default":"/media/commerce/default.png",
                                  "auth":"/media/commerce/auth.png",
                                  "sidenav":null,
                                  "chat":"",
                                  "assistant":"https://cdn.example/assistant.webp",
                                  "toast":"/media/commerce/toast.gif"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logos.default").value("/media/commerce/default.png"))
                .andExpect(jsonPath("$.logos.auth").value("/media/commerce/auth.png"))
                .andExpect(jsonPath("$.logos.sidenav").doesNotExist())
                .andExpect(jsonPath("$.logos.chat").doesNotExist())
                .andExpect(jsonPath("$.logos.assistant").value("https://cdn.example/assistant.webp"))
                .andExpect(jsonPath("$.logos.toast").value("/media/commerce/toast.gif"));

        mockMvc.perform(get(CONFIG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logos.default").value("/media/commerce/default.png"))
                .andExpect(jsonPath("$.logos.auth").value("/media/commerce/auth.png"))
                .andExpect(jsonPath("$.logos.assistant").value("https://cdn.example/assistant.webp"))
                .andExpect(jsonPath("$.logos.toast").value("/media/commerce/toast.gif"));

        mockMvc.perform(get(BRANDING_ADMIN_URL).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logos.default").value("/media/commerce/default.png"));
    }

    @Test
    void recordsSiteBrandingHistoryWhenUrlsChange() throws Exception {
        mockMvc.perform(put(BRANDING_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auth":"/media/commerce/auth-v1.png"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put(BRANDING_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auth":"/media/commerce/auth-v2.png"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get(BRANDING_ADMIN_URL + "/history").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slot").value("auth"))
                .andExpect(jsonPath("$.items[0].oldUrl").value("/media/commerce/auth-v1.png"))
                .andExpect(jsonPath("$.items[0].newUrl").value("/media/commerce/auth-v2.png"))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[1].slot").value("auth"))
                .andExpect(jsonPath("$.items[1].oldUrl").doesNotExist())
                .andExpect(jsonPath("$.items[1].newUrl").value("/media/commerce/auth-v1.png"));
    }

    @Test
    void protectsSiteBrandingWritesWithTheAdminKey() throws Exception {
        mockMvc.perform(put(BRANDING_ADMIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"default":"/media/commerce/default.png"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnsupportedSiteBrandingUrls() throws Exception {
        mockMvc.perform(put(BRANDING_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"default":"javascript:alert(1)"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolvesTheRequestedSiteFromTheAppNameHeader() throws Exception {
        mockMvc.perform(get(CONFIG_URL).header("app-name", "shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("shop"))
                .andExpect(jsonPath("$.appName").value("Shop Site"))
                // the shop site has no settings rows, so the server defaults apply
                .andExpect(jsonPath("$.features.attachment").value(false))
                .andExpect(jsonPath("$.defaultModel").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.announcement").doesNotExist());
    }

    @Test
    void resolvesTheSiteFromTheRequestHostWhenNoHeaderIsSent() throws Exception {
        mockMvc.perform(get(CONFIG_URL).with(request -> {
                    request.setServerName("shop.example.com");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("shop"));
    }

    @Test
    void fallsBackToTheDefaultSiteForAnUnknownHost() throws Exception {
        mockMvc.perform(get(CONFIG_URL).with(request -> {
                    request.setServerName("unknown.example.com");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("default"));
    }

    @Test
    void rejectsAnUnknownSiteHeader() throws Exception {
        mockMvc.perform(get(CONFIG_URL).header("app-name", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("内容不存在")));
    }

    @Test
    void servesTheSamePayloadFromTheLegacyInitPath() throws Exception {
        mockMvc.perform(post(LEGACY_INIT_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("default"))
                .andExpect(jsonPath("$.appName").value("Default Site"))
                .andExpect(jsonPath("$.defaultModel").value("deepseek-v4-pro-0813"));
    }

    @Test
    void publishesBothConfigPathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/config'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v2/config/init'].post").exists())
                .andExpect(jsonPath("$.components.schemas.AppConfigResponse").exists());
    }

    @Test
    void savesEducationAppearanceFromAdminAndPublishesItToTheWorkspace() throws Exception {
        mockMvc.perform(put(EDUCATION_APPEARANCE_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"heroImageUrl":"/media/commerce/teacher.gif"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heroImageUrl").value("/media/commerce/teacher.gif"));

        mockMvc.perform(get(EDUCATION_APPEARANCE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heroImageUrl").value("/media/commerce/teacher.gif"));
    }

    @Test
    void protectsEducationAppearanceWritesWithTheAdminKey() throws Exception {
        mockMvc.perform(put(EDUCATION_APPEARANCE_ADMIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"heroImageUrl":"/media/commerce/teacher.gif"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnsupportedEducationAppearanceUrls() throws Exception {
        mockMvc.perform(put(EDUCATION_APPEARANCE_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"heroImageUrl":"javascript:alert(1)"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savesNavigationVisibilityAndPublishesItInAppConfig() throws Exception {
        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"navigation":{"shop":false,"edu":true}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("default"))
                .andExpect(jsonPath("$.navigation.shop").value(false))
                .andExpect(jsonPath("$.navigation.edu").value(true))
                .andExpect(jsonPath("$.navigation.home").value(true))
                .andExpect(jsonPath("$.navigationSections.length()").value(4));

        mockMvc.perform(get(CONFIG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.shop").value(false))
                .andExpect(jsonPath("$.navigation.edu").value(true))
                .andExpect(jsonPath("$.navigation.home").value(true));
    }

    @Test
    void savesCustomNavigationTitlesAndFunctionAssignments() throws Exception {
        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "navigation":{"shop":false},
                                  "navigationSections":[
                                    {
                                      "id":"workspace",
                                      "title":" 我的工作台 ",
                                      "itemKeys":[
                                        "home","astra","chat","draw","agent","retouch",
                                        "imggen","stock","voice","canvas","image","shop",
                                        "edu","free"
                                      ]
                                    },
                                    {
                                      "id":"media-and-popular",
                                      "title":"媒体与热门",
                                      "itemKeys":[
                                        "3d","video","sound","search","paper","ppt",
                                        "audio","aisound","doc"
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.shop").value(false))
                .andExpect(jsonPath("$.navigationSections.length()").value(2))
                .andExpect(jsonPath("$.navigationSections[0].id").value("workspace"))
                .andExpect(jsonPath("$.navigationSections[0].title").value("我的工作台"))
                .andExpect(jsonPath("$.navigationSections[0].itemKeys[11]").value("shop"))
                .andExpect(jsonPath("$.navigationSections[1].title").value("媒体与热门"));

        mockMvc.perform(get(CONFIG_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.shop").value(false))
                .andExpect(jsonPath("$.navigationSections.length()").value(2))
                .andExpect(jsonPath("$.navigationSections[0].title").value("我的工作台"))
                .andExpect(jsonPath("$.navigationSections[1].id").value("media-and-popular"))
                .andExpect(jsonPath("$.navigationSections[1].itemKeys[8]").value("doc"));
    }

    @Test
    void rejectsNavigationLayoutsWithDuplicateOrMissingFunctions() throws Exception {
        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "navigationSections":[
                                    {
                                      "id":"main",
                                      "title":"主菜单",
                                      "itemKeys":[
                                        "home","home","chat","draw","agent","retouch","imggen",
                                        "stock","voice","canvas","image","shop","edu","free",
                                        "3d","video","sound","search","paper","ppt","audio",
                                        "aisound"
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Duplicate navigation key")));

        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "navigationSections":[
                                    {
                                      "id":"main",
                                      "title":"主菜单",
                                      "itemKeys":[
                                        "home","astra","chat","draw","agent","retouch","imggen",
                                        "stock","voice","canvas","image","shop","edu","free",
                                        "3d","video","sound","search","paper","ppt","audio",
                                        "aisound"
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("missing keys: doc")));
    }

    @Test
    void rejectsNavigationUpdatesWithoutVisibilityOrLayout() throws Exception {
        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("navigation or navigationSections")));
    }

    @Test
    void protectsNavigationSettingsReadsAndWritesWithTheAdminKey() throws Exception {
        mockMvc.perform(get(NAVIGATION_SETTINGS_ADMIN_URL))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"navigation":{"shop":false}}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownNavigationKeys() throws Exception {
        mockMvc.perform(put(NAVIGATION_SETTINGS_ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"navigation":{"unknown-entry":false}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unknown-entry")));
    }
}
