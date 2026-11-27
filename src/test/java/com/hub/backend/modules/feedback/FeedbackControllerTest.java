package com.hub.backend.modules.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.feedback.entity.UserFeedbackEntity;
import com.hub.backend.modules.feedback.repository.UserFeedbackRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedbackControllerTest extends AuthenticatedControllerTestSupport {

    private static final String URL = "/api/v1/feedback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository siteRepository;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private UserFeedbackRepository feedbackRepository;

    private Long siteId;
    private Long userId;
    private String userToken;

    @BeforeEach
    void setUpAccount() {
        AppSiteEntity site = siteRepository.findBySiteKey("feedback-test-site")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("feedback-test-site", "Feedback Test Site")));
        siteId = site.getId();

        UserAccountEntity user = userRepository.save(
                new UserAccountEntity(siteId, "feedback-sender", "Sender"));
        userId = user.getId();
        userToken = accessToken(userId);
    }

    @Test
    void anonymousSubmissionIsStoredWithoutAccount() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BUG", "生成图片时一直转圈，最后报错", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        List<UserFeedbackEntity> rows = feedbackRepository.findAll();
        assertThat(rows).hasSize(1);
        UserFeedbackEntity row = rows.get(0);
        assertThat(row.getCategory()).isEqualTo("BUG");
        assertThat(row.getUserId()).isNull();
        assertThat(row.getSiteId()).isNull();
        assertThat(row.getStatus()).isEqualTo(UserFeedbackEntity.STATUS_NEW);
    }

    @Test
    void authenticatedSubmissionKeepsAccountAndTrimsFields() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("category", "feature");
        body.put("content", "希望余额页能导出账单");
        body.put("contact", "  user@example.com  ");
        body.put("pageUrl", "  /user-center/balance  ");

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated());

        UserFeedbackEntity row = feedbackRepository.findAll().get(0);
        assertThat(row.getUserId()).isEqualTo(userId);
        assertThat(row.getSiteId()).isEqualTo(siteId);
        assertThat(row.getCategory()).isEqualTo("FEATURE");
        assertThat(row.getContact()).isEqualTo("user@example.com");
        assertThat(row.getPageUrl()).isEqualTo("/user-center/balance");
    }

    @Test
    void rejectsContentShorterThanTenCharacters() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("OTHER", "不好用", null)))
                .andExpect(status().isBadRequest());

        assertThat(feedbackRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsUnknownCategory() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SPAM", "这是一条走错类型的反馈内容", null)))
                .andExpect(status().isBadRequest());

        assertThat(feedbackRepository.findAll()).isEmpty();
    }

    @Test
    void rejectsBlankContent() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FEATURE", "          ", null)))
                .andExpect(status().isBadRequest());

        assertThat(feedbackRepository.findAll()).isEmpty();
    }

    private byte[] body(String category, String content, String contact) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("category", category);
        body.put("content", content);
        if (contact != null) body.put("contact", contact);
        return objectMapper.writeValueAsBytes(body);
    }
}
