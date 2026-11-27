package com.hub.backend.modules.admin.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.notification.entity.SiteNotificationEntity;
import com.hub.backend.modules.notification.repository.SiteNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=notification-admin-test-key",
        "app.admin.require-api-key=true"
})
@AutoConfigureMockMvc
@Transactional
class NotificationAdminControllerTest {

    private static final String URL = "/api/admin/v1/notifications";
    private static final String ADMIN_KEY = "notification-admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository siteRepository;

    @Autowired
    private SiteNotificationRepository notificationRepository;

    private AppSiteEntity site;

    @BeforeEach
    void setUpSite() {
        site = siteRepository.findBySiteKey("notification-admin-site")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("notification-admin-site", "Notification Admin Site")));
    }

    @Test
    void protectsTheAdminApiAndValidatesInput() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  ","content":"正文","status":"DRAFT"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"未知站点",
                                  "content":"正文",
                                  "status":"DRAFT",
                                  "siteId":999999
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("siteId")));

        mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"非法级别",
                                  "content":"正文",
                                  "priority":"URGENT",
                                  "status":"DRAFT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("priority")));
    }

    @Test
    void createsFiltersPublishesRetractsAndDeletesNotifications() throws Exception {
        long globalId = createNotification("全局维护", null, "DRAFT", null);
        long siteId = createNotification("站点活动", site.getId(), "PUBLISHED", "HIGH");

        mockMvc.perform(get(URL).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.title == '全局维护')].priority").value("NORMAL"))
                .andExpect(jsonPath("$[?(@.title == '站点活动')].priority").value("HIGH"));

        mockMvc.perform(get(URL)
                        .param("siteId", "0")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(globalId));

        mockMvc.perform(get(URL)
                        .param("status", "PUBLISHED")
                        .param("keyword", "活动")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(siteId));

        mockMvc.perform(put(URL + "/" + globalId + "/status")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").exists());

        mockMvc.perform(put(URL + "/" + globalId)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"全局维护（更新）",
                                  "content":"更新时间另行通知",
                                  "priority":"HIGH",
                                  "status":"DRAFT",
                                  "siteId":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("全局维护（更新）"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(delete(URL + "/" + siteId).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertFalse(notificationRepository.existsById(siteId));
    }

    private long createNotification(String title, Long siteId, String status, String priority) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("content", title + "的正文内容");
        if (priority != null) body.put("priority", priority);
        body.put("status", status);
        body.put("siteId", siteId);

        String response = mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        return created.path("id").asLong();
    }
}
