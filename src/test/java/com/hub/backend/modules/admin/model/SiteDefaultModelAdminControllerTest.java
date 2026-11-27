package com.hub.backend.modules.admin.model;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.entity.AppSiteSettingEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.appconfig.repository.AppSiteSettingRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站点默认对话模型的管理端入口。
 *
 * <p>重点不在「能不能写进去」，而在两件事：写一个目录里没有的 key 必须被拒（否则所有未指定
 * 模型的请求都会 404，而回退逻辑只在日志里留一条 WARN，界面上看不出来）；以及读取时要把
 * 「配置值」和「实际生效值」一起返回，运营才能发现自己的配置已经失效。</p>
 */
@SpringBootTest(properties = {
        "app.admin.require-api-key=true",
        "app.admin.api-key=admin-test-key"
})
@AutoConfigureMockMvc
class SiteDefaultModelAdminControllerTest extends AuthenticatedControllerTestSupport {

    private static final String URL = "/api/admin/v1/site-settings/default-model";
    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppSiteRepository appSiteRepository;
    @Autowired
    private AppSiteSettingRepository appSiteSettingRepository;
    @Autowired
    private LlmModelRepository llmModelRepository;

    private String siteKey;

    @BeforeEach
    void setUp() {
        appSiteSettingRepository.deleteAll();
        llmModelRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(
                new AppSiteEntity("default-model-site", "Default Model Site"));
        siteKey = site.getSiteKey();

        LlmModelEntity chat = new LlmModelEntity(
                "default-model-chat", "Default Model Chat", "Test", "default-model-chat-route");
        chat.setSiteId(site.getId());
        llmModelRepository.save(chat);
    }

    @Test
    @DisplayName("未配置时返回 null 配置值，且生效值是目录里的模型")
    void reportsNothingConfigured() throws Exception {
        mockMvc.perform(get(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .header("app-name", siteKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value((Object) null))
                .andExpect(jsonPath("$.effective").value("default-model-chat"))
                .andExpect(jsonPath("$.fallbackUsed").value(false));
    }

    @Test
    @DisplayName("写入可用的对话模型后，配置值与生效值一致")
    void storesUsableModel() throws Exception {
        mockMvc.perform(put(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .header("app-name", siteKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelKey": "default-model-chat"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value("default-model-chat"))
                .andExpect(jsonPath("$.effective").value("default-model-chat"))
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.configuredValid").value(true));

        assertThat(appSiteSettingRepository
                .findBySiteIdAndSettingKey(currentSiteId(), AppSiteSettingEntity.KEY_DEFAULT_MODEL))
                .isPresent();
    }

    @Test
    @DisplayName("目录里没有的 key 被拒，且不落库")
    void rejectsUnknownModel() throws Exception {
        mockMvc.perform(put(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .header("app-name", siteKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelKey": "not-in-catalogue"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("不是当前站点可用的对话模型")));

        assertThat(appSiteSettingRepository
                .findBySiteIdAndSettingKey(currentSiteId(), AppSiteSettingEntity.KEY_DEFAULT_MODEL))
                .isEmpty();
    }

    @Test
    @DisplayName("停用的模型不能被设为默认")
    void rejectsDisabledModel() throws Exception {
        LlmModelEntity retired = new LlmModelEntity(
                "default-model-retired", "Default Model Retired", "Test", "default-model-retired-route");
        retired.setSiteId(currentSiteId());
        retired.setStatus("DISABLED");
        llmModelRepository.save(retired);

        mockMvc.perform(put(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .header("app-name", siteKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelKey": "default-model-retired"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("传空值清除配置，回到部署默认")
    void clearsConfiguration() throws Exception {
        appSiteSettingRepository.save(new AppSiteSettingEntity(
                currentSiteId(), AppSiteSettingEntity.KEY_DEFAULT_MODEL,
                "default-model-chat", AppSiteSettingEntity.TYPE_STRING));

        mockMvc.perform(put(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .header("app-name", siteKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelKey": ""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value((Object) null));

        assertThat(appSiteSettingRepository
                .findBySiteIdAndSettingKey(currentSiteId(), AppSiteSettingEntity.KEY_DEFAULT_MODEL))
                .isEmpty();
    }

    private Long currentSiteId() {
        return appSiteRepository.findBySiteKey(siteKey).orElseThrow().getId();
    }
}
