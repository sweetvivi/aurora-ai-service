package com.hub.backend.modules.admin.feedback;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.feedback.entity.UserFeedbackEntity;
import com.hub.backend.modules.feedback.repository.UserFeedbackRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=feedback-admin-test-key",
        "app.admin.require-api-key=true"
})
@AutoConfigureMockMvc
@Transactional
class FeedbackAdminControllerTest {

    private static final String URL = "/api/admin/v1/feedback";
    private static final String KEY = "feedback-admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository siteRepository;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private UserFeedbackRepository feedbackRepository;

    private Long userId;

    @BeforeEach
    void seedFeedback() {
        AppSiteEntity site = siteRepository.findBySiteKey("feedback-admin-site")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("feedback-admin-site", "Feedback Admin Site")));
        UserAccountEntity user = userRepository.save(
                new UserAccountEntity(site.getId(), "feedback-admin-user", "Admin User"));
        userId = user.getId();

        save("BUG", "生成图片一直转圈最后报错", "user@example.com", userId);
        save("FEATURE", "希望余额页支持导出账单", null, null);
        UserFeedbackEntity handled = save("OTHER", "这条已经处理过了", null, null);
        handled.setStatus(UserFeedbackEntity.STATUS_HANDLED);
        feedbackRepository.saveAndFlush(handled);
    }

    @Test
    void requiresAdminKey() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void listsNewestFirstWithPendingCount() throws Exception {
        mockMvc.perform(get(URL).header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.pendingCount").value(2))
                .andExpect(jsonPath("$.list", hasSize(3)))
                .andExpect(jsonPath("$.list[0].content").value("这条已经处理过了"))
                .andExpect(jsonPath("$.list[0].status").value("HANDLED"));
    }

    @Test
    void filtersByStatusAndCategory() throws Exception {
        mockMvc.perform(get(URL).param("status", "NEW").header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get(URL).param("category", "FEATURE").header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].contact").value(""));
    }

    @Test
    void keywordMatchesContentContactUsernameAndId() throws Exception {
        mockMvc.perform(get(URL).param("keyword", "user@example.com").header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get(URL).param("keyword", "feedback-admin-user").header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].username").value("feedback-admin-user"));

        mockMvc.perform(get(URL).param("keyword", "导出账单").header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void blankFiltersMeanNoFilter() throws Exception {
        mockMvc.perform(get(URL).param("status", "").param("category", "  ")
                        .header("X-Admin-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void rejectsUnknownStatusFilter() throws Exception {
        mockMvc.perform(get(URL).param("status", "SPAM").header("X-Admin-Key", KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void togglesHandledStatus() throws Exception {
        Long id = feedbackRepository.findAll().stream()
                .filter(item -> item.getContent().contains("一直转圈"))
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(put(URL + "/" + id + "/status")
                        .header("X-Admin-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HANDLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDLED"));

        // 原本 2 条待处理（BUG / FEATURE），处理掉 BUG 后只剩 FEATURE。
        mockMvc.perform(get(URL).header("X-Admin-Key", KEY))
                .andExpect(jsonPath("$.pendingCount").value(1));

        mockMvc.perform(put(URL + "/" + id + "/status")
                        .header("X-Admin-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void rejectsUnknownStatusValue() throws Exception {
        Long id = feedbackRepository.findAll().get(0).getId();

        mockMvc.perform(put(URL + "/" + id + "/status")
                        .header("X-Admin-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusUpdateOnMissingRowIsNotFound() throws Exception {
        mockMvc.perform(put(URL + "/999999/status")
                        .header("X-Admin-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HANDLED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesFeedback() throws Exception {
        Long id = feedbackRepository.findAll().get(0).getId();

        mockMvc.perform(delete(URL + "/" + id).header("X-Admin-Key", KEY))
                .andExpect(status().isNoContent());

        assertThat(feedbackRepository.findById(id)).isEmpty();

        mockMvc.perform(delete(URL + "/" + id).header("X-Admin-Key", KEY))
                .andExpect(status().isNotFound());
    }

    private UserFeedbackEntity save(String category, String content, String contact, Long owner) {
        UserFeedbackEntity entity = new UserFeedbackEntity();
        entity.setCategory(category);
        entity.setContent(content);
        entity.setContact(contact);
        entity.setUserId(owner);
        return feedbackRepository.saveAndFlush(entity);
    }
}
