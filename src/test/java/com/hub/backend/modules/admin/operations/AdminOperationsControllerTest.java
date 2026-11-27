package com.hub.backend.modules.admin.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.aicanvas.entity.AiCanvasTaskEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasTaskRepository;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=admin-test-key",
        "app.new-api.catalog-sync-on-startup=false"
})
@AutoConfigureMockMvc
@Transactional
class AdminOperationsControllerTest {

    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private AiCanvasTaskRepository taskRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private AppSiteRepository siteRepository;

    @Test
    void protectsEveryAdminOperationWithTheAdminKey() throws Exception {
        mockMvc.perform(get("/api/admin/v1/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/v1/conversations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/v1/tasks"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin-no-key\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/admin/v1/users/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/admin/v1/conversations/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsSearchesAndChangesUserStatus() throws Exception {
        String username = username("create");

        mockMvc.perform(post("/api/admin/v1/users")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "nickname", "管理用户",
                                "remark", "运营备注"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.nickname").value("管理用户"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.remark").value("运营备注"))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/admin/v1/users")
                        .param("keyword", username)
                        .param("status", "active")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].username").value(username));

        Long id = userRepository.findFirstBySiteIdAndUsername(
                defaultSite().getId(), username).orElseThrow().getId();
        mockMvc.perform(patch("/api/admin/v1/users/{id}/status", id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/admin/v1/users")
                        .param("keyword", username)
                        .param("status", "disabled")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].status").value("disabled"));

        mockMvc.perform(patch("/api/admin/v1/users/{id}/status", id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"locked\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/admin/v1/users/{id}/status", Long.MAX_VALUE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateUsernamesAndDeletesUnreferencedUsers() throws Exception {
        String username = username("delete");
        mockMvc.perform(post("/api/admin/v1/users")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/users")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username))))
                .andExpect(status().isConflict());

        Long id = userRepository.findFirstBySiteIdAndUsername(
                defaultSite().getId(), username).orElseThrow().getId();
        mockMvc.perform(delete("/api/admin/v1/users/{id}", id)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertFalse(userRepository.existsById(id));

        mockMvc.perform(delete("/api/admin/v1/users/{id}", id)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsAndSoftDeletesConversationsUsingTheModelKey() throws Exception {
        String suffix = suffix();
        UserAccountEntity user = userRepository.saveAndFlush(new UserAccountEntity(
                defaultSite().getId(), "conversation-user-" + suffix, "Conversation User"));
        LlmModelEntity model = modelRepository.saveAndFlush(
                new LlmModelEntity("conversation-model-" + suffix, "Conversation Model",
                        "Test", "conversation-model-" + suffix));
        ConversationEntity conversation = new ConversationEntity(
                user.getId(), model.getId(), "会话搜索-" + suffix);
        conversation.registerMessages(6);
        conversationRepository.saveAndFlush(conversation);

        mockMvc.perform(get("/api/admin/v1/conversations")
                        .param("keyword", "会话搜索-" + suffix)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].username").value(user.getUsername()))
                .andExpect(jsonPath("$.list[0].title").value("会话搜索-" + suffix))
                .andExpect(jsonPath("$.list[0].modelId").value(model.getModelKey()))
                .andExpect(jsonPath("$.list[0].messageCount").value(6))
                .andExpect(jsonPath("$.list[0].status").value("active"));

        mockMvc.perform(delete("/api/admin/v1/conversations/{id}", conversation.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertEquals(
                ConversationEntity.STATUS_DELETED,
                conversationRepository.findById(conversation.getId()).orElseThrow().getStatus());
        mockMvc.perform(get("/api/admin/v1/conversations")
                        .param("keyword", "会话搜索-" + suffix)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(delete("/api/admin/v1/conversations/{id}", conversation.getId())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsFiltersAndPagesImageTasks() throws Exception {
        String suffix = suffix();
        UserAccountEntity user = userRepository.saveAndFlush(new UserAccountEntity(
                defaultSite().getId(), "task-user-" + suffix, "Task User"));
        String keyword = "task-search-" + suffix;

        task(user, "task-pending-a-" + suffix, keyword + " pending a",
                AiCanvasTaskEntity.STATUS_PENDING, "model-a", null);
        task(user, "task-pending-b-" + suffix, keyword + " pending b",
                AiCanvasTaskEntity.STATUS_PENDING, null, "upstream-b");
        task(user, "task-running-" + suffix, keyword + " running",
                AiCanvasTaskEntity.STATUS_RUNNING, "model-running", null);
        taskRepository.saveAndFlush(new AiCanvasTaskEntity(
                "task-video-" + suffix,
                user.getId(),
                AiCanvasTaskEntity.TYPE_VIDEO,
                null,
                "video-model",
                null,
                keyword + " video",
                AiCanvasTaskEntity.STATUS_RUNNING,
                10,
                null,
                null));

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .param("keyword", keyword)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[*].id", not(hasItem("task-video-" + suffix))));

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .param("keyword", keyword)
                        .param("status", "generating")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].id").value("task-running-" + suffix))
                .andExpect(jsonPath("$.list[0].status").value("generating"))
                .andExpect(jsonPath("$.list[0].model").value("model-running"))
                .andExpect(jsonPath("$.list[0].username").value(user.getUsername()));

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .param("keyword", keyword)
                        .param("page", "2")
                        .param("pageSize", "2")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list.length()").value(1));

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .param("status", "in-progress")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest());
    }

    private void task(UserAccountEntity user,
                      String id,
                      String prompt,
                      String status,
                      String modelKey,
                      String upstreamModel) {
        taskRepository.save(new AiCanvasTaskEntity(
                id,
                user.getId(),
                AiCanvasTaskEntity.TYPE_IMAGE,
                null,
                modelKey,
                upstreamModel,
                prompt,
                status,
                0,
                null,
                null));
    }

    private AppSiteEntity defaultSite() {
        return siteRepository.findBySiteKey("default")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("default", "Default Site")));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String username(String purpose) {
        return "admin-" + purpose + "-" + suffix();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
