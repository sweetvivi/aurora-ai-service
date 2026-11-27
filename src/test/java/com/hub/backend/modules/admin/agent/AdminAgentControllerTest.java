package com.hub.backend.modules.admin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.agent.entity.AgentCategoryEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.repository.AgentAbilityRepository;
import com.hub.backend.modules.agent.repository.AgentCategoryRepository;
import com.hub.backend.modules.agent.repository.AgentChatMessageRepository;
import com.hub.backend.modules.agent.repository.AgentConversationRepository;
import com.hub.backend.modules.agent.repository.AgentFavoriteRepository;
import com.hub.backend.modules.agent.repository.AgentModelRepository;
import com.hub.backend.modules.agent.repository.AgentQuestionRepository;
import com.hub.backend.modules.agent.repository.AgentRepository;
import com.hub.backend.modules.agent.repository.AgentTagRepository;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.repository.ChatMessageRepository;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.require-api-key=true",
        "app.admin.api-key=admin-test-key"
})
@AutoConfigureMockMvc
class AdminAgentControllerTest extends AuthenticatedControllerTestSupport {

    private static final String URL = "/api/admin/v1/agents";
    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AppSiteRepository appSiteRepository;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private LlmModelRepository llmModelRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private AgentCategoryRepository agentCategoryRepository;
    @Autowired
    private AgentTagRepository agentTagRepository;
    @Autowired
    private AgentAbilityRepository agentAbilityRepository;
    @Autowired
    private AgentModelRepository agentModelRepository;
    @Autowired
    private AgentQuestionRepository agentQuestionRepository;
    @Autowired
    private AgentFavoriteRepository agentFavoriteRepository;
    @Autowired
    private AgentConversationRepository agentConversationRepository;
    @Autowired
    private AgentChatMessageRepository agentChatMessageRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private Long siteId;
    private LlmModelEntity model;

    @BeforeEach
    void setUp() {
        agentChatMessageRepository.deleteAll();
        agentConversationRepository.deleteAll();
        agentFavoriteRepository.deleteAll();
        agentQuestionRepository.deleteAll();
        agentModelRepository.deleteAll();
        agentAbilityRepository.deleteAll();
        agentTagRepository.deleteAll();
        agentRepository.deleteAll();
        agentCategoryRepository.deleteAll();
        chatMessageRepository.deleteAll();
        conversationRepository.deleteAll();
        llmModelRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("admin-agent-site", "Admin Agent Site"));
        siteId = site.getId();
        model = new LlmModelEntity(
                "admin-agent-model", "Admin Agent Model", "Test", "admin-agent-model-route");
        model.setSiteId(siteId);
        model = llmModelRepository.save(model);
    }

    @Test
    void crudLifecycleMaintainsOfficialAgentAndSoftDeletesIt() throws Exception {
        String createdBody = mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "siteId": %d,
                                  "name": "Admin Managed Agent",
                                  "description": "Managed from the console",
                                  "systemPrompt": "Answer as an operator-managed agent.",
                                  "chatPrologue": "How can I help?",
                                  "scope": 1,
                                  "visibility": "PUBLIC",
                                  "publishStatus": "PUBLISHED",
                                  "score": 8.40,
                                  "hot": true,
                                  "sortOrder": 7,
                                  "tags": ["ops", "support"],
                                  "modelIds": [%d],
                                  "defaultModelId": %d,
                                  "questions": ["Reset a password"]
                                }
                                """.formatted(siteId, model.getId(), model.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.ownerUserId").value(nullValue()))
                .andExpect(jsonPath("$.agentType").value(AgentEntity.TYPE_OFFICIAL))
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_ACTIVE))
                .andExpect(jsonPath("$.sortOrder").value(7))
                .andExpect(jsonPath("$.hot").value(true))
                .andExpect(jsonPath("$.tags[0]").value("ops"))
                .andExpect(jsonPath("$.modelIds[0]").value(model.getId().intValue()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long agentId = objectMapper.readTree(createdBody).path("id").asLong();
        String agentSn = objectMapper.readTree(createdBody).path("sn").asText();
        UserAccountEntity publicReader = userAccountRepository.save(
                new UserAccountEntity(siteId, "admin-agent-public-reader", "Admin Agent Public Reader"));
        String publicReaderToken = accessToken(publicReader.getId());

        mockMvc.perform(get(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("keyword", "Managed Agent")
                        .param("sort", "sort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(agentId))
                .andExpect(jsonPath("$.items[0].status").value(AgentEntity.STATUS_ACTIVE));

        mockMvc.perform(get(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Admin Managed Agent"))
                .andExpect(jsonPath("$.sortOrder").value(7));

        mockMvc.perform(post(URL + "/" + agentId + "/unpublish")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_UNPUBLISHED))
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_PUBLISHED));

        mockMvc.perform(get("/api/v1/agents/{reference}", agentSn)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + publicReaderToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(put(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "ACTIVE" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_ACTIVE))
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_PUBLISHED));

        mockMvc.perform(get("/api/v1/agents/{reference}", agentSn)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + publicReaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_PUBLISHED));

        mockMvc.perform(put(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "UNPUBLISHED" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_UNPUBLISHED))
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_PUBLISHED));

        mockMvc.perform(put(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Admin Managed Agent Updated",
                                  "status": "DELETED",
                                  "score": 9.10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Admin Managed Agent Updated"))
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_DELETED))
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_DELETED))
                .andExpect(jsonPath("$.visibility").value(AgentEntity.VISIBILITY_PRIVATE))
                .andExpect(jsonPath("$.score").value(9.1));

        mockMvc.perform(get("/api/v1/agents/{id}", agentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + publicReaderToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("status", AgentEntity.STATUS_DELETED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(agentId));

        mockMvc.perform(get(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_DELETED))
                .andExpect(jsonPath("$.name").value("Admin Managed Agent Updated"));

        mockMvc.perform(delete(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(URL + "/" + agentId)
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_DELETED));
    }

    @Test
    void listShowsActiveAndDeletedRowsUnlessFiltered() throws Exception {
        AgentEntity active = publicAgent("admin-agent-active", "Active Agent");
        active.setSortOrder(2);
        agentRepository.save(active);

        AgentEntity deleted = publicAgent("admin-agent-deleted", "Deleted Agent");
        deleted.softDelete();
        agentRepository.save(deleted);

        mockMvc.perform(get(URL).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(get(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("status", AgentEntity.STATUS_ACTIVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Active Agent"));

        mockMvc.perform(get(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("keyword", "Deleted Agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value(AgentEntity.STATUS_DELETED));
    }

    @Test
    void listsCategoriesWithOnlyTheAdminKey() throws Exception {
        agentCategoryRepository.save(
                new AgentCategoryEntity(siteId, "admin-agent-category", "Admin Agent Category"));

        mockMvc.perform(get(URL + "/categories").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryKey").value("admin-agent-category"))
                .andExpect(jsonPath("$[0].name").value("Admin Agent Category"));

        mockMvc.perform(get(URL + "/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownOwnerWhenCreatingAgent() throws Exception {
        long unknownOwnerId = 999_999L;

        mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "siteId": %d,
                                  "ownerUserId": %d,
                                  "name": "Invalid Owner Agent",
                                  "systemPrompt": "This must not be persisted."
                                }
                                """.formatted(siteId, unknownOwnerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("归属用户不存在：" + unknownOwnerId));

        org.assertj.core.api.Assertions.assertThat(agentRepository.count()).isZero();
    }

    @Test
    void rejectsUnknownOwnerWhenUpdatingAgentWithoutChangingExistingData() throws Exception {
        AgentEntity existing = agentRepository.save(publicAgent("admin-agent-owner-check", "Before Update"));
        long unknownOwnerId = 999_999L;

        mockMvc.perform(put(URL + "/" + existing.getId())
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerUserId": %d,
                                  "name": "After Update"
                                }
                                """.formatted(unknownOwnerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("归属用户不存在：" + unknownOwnerId));

        AgentEntity unchanged = agentRepository.findById(existing.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(unchanged.getName()).isEqualTo("Before Update");
        org.assertj.core.api.Assertions.assertThat(unchanged.getOwnerUserId()).isNull();
    }

    @Test
    void rejectsRequestsWithoutTheAdminKeyWhenEnabled() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishesAdminAgentRoutesInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents'].post").exists())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents/categories'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents/{id}/unpublish'].post").exists())
                .andExpect(jsonPath("$.paths['/api/admin/v1/agents/{id}'].delete").exists());
    }

    /**
     * 一个智能体只能绑一个对话模型。
     *
     * <p>放开多个会让前台冒出模型切换入口，而站内没有余额/限流闸门，用户切到贵档模型的成本
     * 全落在站点身上。这条约束在 DTO 与 service 两处各守一次，这里验的是请求边界。</p>
     */
    @Test
    void rejectsMoreThanOneModelPerAgent() throws Exception {
        LlmModelEntity second = llmModelRepository.save(new LlmModelEntity(
                "admin-agent-model-2", "Admin Agent Model 2", "Test", "admin-agent-model-route-2"));

        mockMvc.perform(post(URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "siteId": %d,
                                  "name": "Two Model Agent",
                                  "systemPrompt": "Should be rejected.",
                                  "modelIds": [%d, %d],
                                  "defaultModelId": %d
                                }
                                """.formatted(siteId, model.getId(), second.getId(), model.getId())))
                .andExpect(status().isBadRequest())
                // 校验失败走统一处理器：message 是固定英文，具体字段原因在 details 里。
                .andExpect(jsonPath("$.details.modelIds").value(containsString("只能绑定一个对话模型")));
    }

    private AgentEntity publicAgent(String sn, String name) {
        AgentEntity agent = new AgentEntity(siteId, null, sn, sn, name);
        agent.setSystemPrompt("Managed by the admin API.");
        agent.setScope(AgentEntity.SCOPE_PUBLIC);
        agent.setVisibility(AgentEntity.VISIBILITY_PUBLIC);
        agent.setPublishStatus(AgentEntity.PUBLISH_PUBLISHED);
        return agent;
    }
}
