package com.hub.backend.modules.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=notification-user-test-key",
        "app.admin.require-api-key=true"
})
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTest extends AuthenticatedControllerTestSupport {

    private static final String USER_URL = "/api/v1/notifications";
    private static final String ADMIN_URL = "/api/admin/v1/notifications";
    private static final String ADMIN_KEY = "notification-user-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository siteRepository;

    @Autowired
    private UserAccountRepository userRepository;

    private AppSiteEntity site;
    private Long siteNotificationId;
    private Long otherSiteNotificationId;
    private String userToken;
    private String otherUserToken;

    @BeforeEach
    void setUpAccountsAndNotifications() throws Exception {
        site = siteRepository.findBySiteKey("notification-user-site")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("notification-user-site", "Notification User Site")));
        AppSiteEntity otherSite = siteRepository.findBySiteKey("notification-other-site")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("notification-other-site", "Notification Other Site")));

        UserAccountEntity user = userRepository.save(
                new UserAccountEntity(site.getId(), "notification-reader", "Reader"));
        UserAccountEntity otherUser = userRepository.save(
                new UserAccountEntity(site.getId(), "notification-other-reader", "Other Reader"));
        userToken = accessToken(user.getId());
        otherUserToken = accessToken(otherUser.getId());

        createNotification("全局公告", null, "PUBLISHED", null);
        siteNotificationId = createNotification("本站公告", site.getId(), "PUBLISHED", "HIGH");
        otherSiteNotificationId = createNotification("其他站公告", otherSite.getId(), "PUBLISHED", null);
        createNotification("本站草稿", site.getId(), "DRAFT", null);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(USER_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsOnlyPublishedGlobalAndCurrentSiteNotifications() throws Exception {
        mockMvc.perform(get(USER_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.title == '全局公告')].priority").value("NORMAL"))
                .andExpect(jsonPath("$[?(@.title == '本站公告')].priority").value("HIGH"))
                .andExpect(jsonPath("$[?(@.title == '本站草稿')]").isEmpty())
                .andExpect(jsonPath("$[?(@.title == '其他站公告')]").isEmpty());

        mockMvc.perform(get(USER_URL + "/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void marksOneThenAllNotificationsReadIdempotentlyPerAccount() throws Exception {
        mockMvc.perform(post(USER_URL + "/" + siteNotificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(USER_URL + "/" + siteNotificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(USER_URL + "/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        mockMvc.perform(post(USER_URL + "/read-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedRead").value(1));
        mockMvc.perform(post(USER_URL + "/read-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedRead").value(0));

        mockMvc.perform(get(USER_URL + "/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        mockMvc.perform(get(USER_URL + "/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void cannotMarkANotificationFromAnotherSiteAsRead() throws Exception {
        mockMvc.perform(post(USER_URL + "/" + otherSiteNotificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    private long createNotification(String title, Long siteId, String status, String priority) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("content", title + "的正文内容");
        if (priority != null) body.put("priority", priority);
        body.put("status", status);
        body.put("siteId", siteId);

        String response = mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("id").asLong();
    }
}
