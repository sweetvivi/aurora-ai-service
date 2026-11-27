package com.hub.backend.modules.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.agent.entity.AgentConversationEntity;
import com.hub.backend.modules.agent.entity.AgentCategoryEntity;
import com.hub.backend.modules.agent.entity.AgentChatMessageEntity;
import com.hub.backend.modules.agent.entity.AgentEntity;
import com.hub.backend.modules.agent.repository.AgentCategoryRepository;
import com.hub.backend.modules.agent.repository.AgentAbilityRepository;
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
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import com.hub.backend.modules.gateway.UserRelayCredentialResolver;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerTest extends AuthenticatedControllerTestSupport {

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
    private CommerceToolRepository commerceToolRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private UserRelayCredentialResolver relayCredentials;

    private Long siteId;
    private LlmModelEntity model;

    @BeforeEach
    void setUp() {
        agentChatMessageRepository.deleteAll();
        commerceToolRepository.deleteAll();
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

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        siteId = site.getId();
        model = new LlmModelEntity(
                "deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek",
                "deepseek-v4.1-flash-0910");
        model.setSiteId(siteId);
        model = llmModelRepository.save(model);
        when(relayCredentials.resolve(any(), any())).thenReturn(new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-user-relay",
                null,
                null,
                null));
    }

    @Test
    void exposesTheAgentPageFieldsAndIncrementsViewsOnDetail() throws Exception {
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "agent-viewer", "Agent Viewer"));
        String userToken = accessToken(user.getId());
        AgentEntity agent = publicAgent("python-pro", "Python Expert");
        agent.setDescription("Writes and reviews Python code.");
        agent.setScore(new BigDecimal("8.50"));
        agent.setSeeCount(3);
        agent = agentRepository.save(agent);

        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Python Expert"))
                .andExpect(jsonPath("$.items[0].description")
                        .value("Writes and reviews Python code."))
                .andExpect(jsonPath("$.items[0].score").value(8.5))
                .andExpect(jsonPath("$.items[0].seeCount").value(3));

        mockMvc.perform(get("/api/v1/agents/{id}", agent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Writes and reviews Python code."))
                .andExpect(jsonPath("$.score").value(8.5))
                .andExpect(jsonPath("$.seeCount").value(4));
    }

    @Test
    void filtersTheCatalogueByExactChildClassification() throws Exception {
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "education-viewer", "Education Viewer"));
        String userToken = accessToken(user.getId());

        AgentEntity teacher = publicAgent("lesson-planner", "Lesson Planner");
        teacher.setChildClassify("教师工具");
        agentRepository.save(teacher);
        AgentEntity family = publicAgent("parent-helper", "Parent Helper");
        family.setChildClassify("家庭教育");
        agentRepository.save(family);

        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("childClassify", "教师工具"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sn").value("lesson-planner"))
                .andExpect(jsonPath("$.items[0].childClassify").value("教师工具"));
    }

    @Test
    void excludesOneCategoryWithoutDroppingUncategorisedAgents() throws Exception {
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "catalogue-viewer", "Catalogue Viewer"));
        String userToken = accessToken(user.getId());
        AgentCategoryEntity education = agentCategoryRepository.save(
                new AgentCategoryEntity(siteId, "ai-education", "AI Education"));

        AgentEntity hidden = publicAgent("education-only", "Education Only");
        hidden.setCategoryId(education.getId());
        agentRepository.save(hidden);
        agentRepository.save(publicAgent("general-agent", "General Agent"));

        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("excludeCategoryId", education.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sn").value("general-agent"));
    }

    @Test
    void keepsTheFavouriteCounterInStepWithTheFavouriteRows() throws Exception {
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "agent-fan", "Agent Fan"));
        String userToken = accessToken(user.getId());
        AgentEntity agent = publicAgent("favourite-agent", "Favourite Agent");
        agent = agentRepository.save(agent);

        mockMvc.perform(post("/api/v1/agents/{id}/favorite", agent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true))
                .andExpect(jsonPath("$.favoriteCount").value(1));

        mockMvc.perform(delete("/api/v1/agents/{id}/favorite", agent.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false))
                .andExpect(jsonPath("$.favoriteCount").value(0));
    }

    @Test
    void allowsAnonymousPublicCatalogueBrowsingButKeepsPrivateActionsProtected() throws Exception {
        AgentCategoryEntity category = agentCategoryRepository.save(
                new AgentCategoryEntity(siteId, "general", "通用助手"));
        AgentEntity agent = publicAgent("public-anonymous", "Anonymous Public Agent");
        agent.setCategoryId(category.getId());
        agentRepository.save(agent);

        mockMvc.perform(get("/api/v1/agents/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryKey").value("general"));

        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sn").value("public-anonymous"))
                .andExpect(jsonPath("$.items[0].favorite").value(false));

        mockMvc.perform(get("/api/v1/agents/{reference}", agent.getSn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Anonymous Public Agent"));

        mockMvc.perform(post("/api/v1/agents/{id}/favorite", agent.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/agents/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        for (String filter : java.util.List.of("mine", "favorite")) {
            mockMvc.perform(get("/api/v1/agents").param(filter, "true"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Anonymous Agent","systemPrompt":"Help the user."}"""))
                .andExpect(status().isUnauthorized());

        for (String endpoint : java.util.List.of("completions", "stream")) {
            mockMvc.perform(post("/api/v1/agents/{sn}/chat/{endpoint}", agent.getSn(), endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"messages":[{"role":"user","content":"hi"}]}"""))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(0, agentConversationRepository.count());
        assertEquals(0, agentChatMessageRepository.count());
        verifyNoInteractions(llmClient, relayCredentials);
    }

    @Test
    void anonymousCatalogueExcludesNonPublicAgentsAndOtherSites() throws Exception {
        AgentEntity visible = agentRepository.save(publicAgent("visible", "Visible Agent"));
        AgentEntity draft = publicAgent("draft", "Draft Agent");
        draft.setPublishStatus(AgentEntity.PUBLISH_DRAFT);
        AgentEntity privateAgent = publicAgent("private", "Private Agent");
        privateAgent.setVisibility(AgentEntity.VISIBILITY_PRIVATE);
        AgentEntity privateScope = publicAgent("private-scope", "Private Scope Agent");
        privateScope.setScope(AgentEntity.SCOPE_PRIVATE);
        AgentEntity deleted = publicAgent("deleted", "Deleted Agent");
        deleted.setStatus(AgentEntity.STATUS_DELETED);
        AppSiteEntity otherSite = appSiteRepository.save(new AppSiteEntity("other", "Other Site"));
        AgentEntity otherSiteAgent = publicAgent("other-site", "Other Site Agent");
        otherSiteAgent.setSiteId(otherSite.getId());
        var hiddenAgents = agentRepository.saveAll(
                java.util.List.of(draft, privateAgent, privateScope, deleted, otherSiteAgent));

        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sn").value(visible.getSn()));
        mockMvc.perform(get("/api/v1/agents").param("scope", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        for (AgentEntity hidden : hiddenAgents) {
            mockMvc.perform(get("/api/v1/agents/{reference}", hidden.getSn()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/agents/{reference}", hidden.getId()))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/v1/agents").header("app-name", "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sn").value(otherSiteAgent.getSn()));
        mockMvc.perform(get("/api/v1/agents/{reference}", visible.getSn())
                        .header("app-name", "other"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidBearerTokensAreNotTreatedAsAnonymousCatalogueRequests() throws Exception {
        AgentEntity agent = agentRepository.save(publicAgent("invalid-token", "Public Agent"));
        for (String endpoint : java.util.List.of("", "/categories", "/" + agent.getSn())) {
            mockMvc.perform(get("/api/v1/agents" + endpoint)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void createsAnOwnedPrivateAgentAndListsOnlyTheCurrentUsersAgents() throws Exception {
        UserAccountEntity alice = userAccountRepository.save(
                new UserAccountEntity(siteId, "agent-alice", "alice"));
        UserAccountEntity bob = userAccountRepository.save(
                new UserAccountEntity(siteId, "agent-bob", "bob"));
        String aliceToken = accessToken(alice.getId());
        String bobToken = accessToken(bob.getId());

        String aliceBody = mockMvc.perform(post("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice Agent",
                                  "description": "Only Alice owns this agent",
                                  "systemPrompt": "Answer as Alice's assistant."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(alice.getId().intValue()))
                .andExpect(jsonPath("$.agentType").value(AgentEntity.TYPE_CUSTOM))
                .andExpect(jsonPath("$.scope").value(AgentEntity.SCOPE_PRIVATE))
                .andExpect(jsonPath("$.visibility").value(AgentEntity.VISIBILITY_PRIVATE))
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_DRAFT))
                .andExpect(jsonPath("$.status").value(AgentEntity.STATUS_ACTIVE))
                .andExpect(jsonPath("$.score").value(5.0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long aliceAgentId = objectMapper
                .readTree(aliceBody)
                .path("id")
                .asLong();

        mockMvc.perform(patch("/api/v1/agents/{id}", aliceAgentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Alice Agent Updated",
                                  "scope": 1,
                                  "visibility": "PUBLIC",
                                  "publishStatus": "PUBLISHED",
                                  "score": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Agent Updated"))
                .andExpect(jsonPath("$.scope").value(AgentEntity.SCOPE_PRIVATE))
                .andExpect(jsonPath("$.visibility").value(AgentEntity.VISIBILITY_PRIVATE))
                .andExpect(jsonPath("$.publishStatus").value(AgentEntity.PUBLISH_DRAFT))
                .andExpect(jsonPath("$.score").value(5.0));

        mockMvc.perform(post("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Bob Agent",
                                  "systemPrompt": "Answer as Bob's assistant."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(bob.getId().intValue()));

        AgentEntity officialAgent = agentRepository.save(
                publicAgent("mine-official-agent-row", "Official Agent Row"));

        // A user's private draft belongs only in "mine", never in the public catalogue.
        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(officialAgent.getId()));

        mockMvc.perform(get("/api/v1/agents/mine")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(aliceAgentId))
                .andExpect(jsonPath("$.items[0].ownerUserId").value(alice.getId().intValue()))
                .andExpect(jsonPath("$.items[0].name").value("Alice Agent Updated"));

        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .param("mine", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(aliceAgentId));

        // Private agents remain visible in the owner's favourite list if explicitly favourited.
        mockMvc.perform(post("/api/v1/agents/{id}/favorite", aliceAgentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));

        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .param("favorite", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(aliceAgentId));

        mockMvc.perform(get("/api/v1/agents/mine")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Bob Agent"));
    }

    @Test
    void allowsAnOwnedAgentToReferenceOnlyAnActiveCommerceTool() throws Exception {
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "commerce-agent-user", "Commerce Agent User"));
        String token = accessToken(user.getId());
        CommerceToolEntity tool = CommerceToolEntity.newTool();
        tool.setSiteId(siteId);
        tool.setToolKey("one-click-background");
        tool.setCategory("商品处理");
        tool.setName("一键换背景");
        tool.setForm("image");
        tool.setStatus(CommerceToolEntity.STATUS_ACTIVE);
        commerceToolRepository.save(tool);

        String body = """
                {
                  "name": "商品抠图助手",
                  "description": "绑定商品处理工具的智能体",
                  "systemPrompt": "帮助用户处理商品图片",
                  "linkedToolKey": "one-click-background"
                }
                """;

        mockMvc.perform(post("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedToolKey").value("one-click-background"));

        mockMvc.perform(post("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("one-click-background", "missing-tool")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        AgentEntity created = agentRepository.findAll().stream()
                .filter(item -> "商品抠图助手".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        mockMvc.perform(patch("/api/v1/agents/{id}", created.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkedToolKey\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedToolKey").doesNotExist());
    }

    @Test
    void chatUsesTheReusableProtocolAndStoresHistoryOnlyInAgentTables() throws Exception {
        AgentEntity agent = publicAgent("python-pro", "Python Expert");
        agent.setSystemPrompt("Answer as the Python specialist.");
        agent = agentRepository.save(agent);
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "agent-chat-user", "Agent Chat User"));
        String userToken = accessToken(user.getId());
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", "deepseek-v4.1-flash-0910", "pong", "stop",
                new LlmUsage(10, 2, 12)));

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/completions", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"say pong"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("pong"))
                .andExpect(jsonPath("$.conversationId").isNumber())
                .andExpect(jsonPath("$.messageId").isNumber())
                .andExpect(jsonPath("$.usage.totalTokens").value(12));

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals(2, command.getValue().messages().size());
        assertEquals("system", command.getValue().messages().get(0).role());
        assertEquals("Answer as the Python specialist.", command.getValue().messages().get(0).content());
        assertEquals("say pong", command.getValue().messages().get(1).content());

        assertEquals(1, agentConversationRepository.count());
        assertEquals(2, agentChatMessageRepository.count());
        assertEquals(0, conversationRepository.count());
        assertEquals(0, chatMessageRepository.count());
    }

    @Test
    void rejectsBothAgentChatEndpointsBeforePersistenceWhenTheUserBalanceIsZero()
            throws Exception {
        AgentEntity agent = agentRepository.save(publicAgent("unpaid-agent", "Unpaid Agent"));
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "unpaid-user", "Unpaid User"));
        String userToken = accessToken(user.getId());
        doThrow(new PaymentRequiredException("Insufficient account balance"))
                .when(relayCredentials).resolve(any(), any());
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/completions", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/stream", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        assertEquals(0, agentConversationRepository.count());
        assertEquals(0, agentChatMessageRepository.count());
        verifyNoInteractions(llmClient);
    }

    @Test
    void doesNotExposeAnotherAccountsAgentConversation() throws Exception {
        AgentEntity agent = publicAgent("private-history", "Private History");
        agent = agentRepository.save(agent);
        UserAccountEntity alice = userAccountRepository.save(
                new UserAccountEntity(siteId, "alice", "alice"));
        UserAccountEntity bob = userAccountRepository.save(
                new UserAccountEntity(siteId, "bob", "bob"));
        String aliceToken = accessToken(alice.getId());
        String bobToken = accessToken(bob.getId());
        AgentConversationEntity conversation = agentConversationRepository.save(
                new AgentConversationEntity(siteId, alice.getId(), agent.getId(), model.getId(), "secret"));

        mockMvc.perform(get("/api/v1/agents/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agent.getId().intValue()));

        mockMvc.perform(get("/api/v1/agents/conversations/{id}", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void filtersConversationHistoryByAgentCategory() throws Exception {
        AgentCategoryEntity educationCategory = agentCategoryRepository.save(
                new AgentCategoryEntity(siteId, "ai-education", "AI 教育"));
        AgentEntity educationAgent = publicAgent("education-history", "教育助手");
        educationAgent.setCategoryId(educationCategory.getId());
        educationAgent = agentRepository.save(educationAgent);
        AgentEntity generalAgent = agentRepository.save(publicAgent("general-history", "通用助手"));
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "education-history-user", "Education User"));
        String userToken = accessToken(user.getId());
        agentConversationRepository.save(new AgentConversationEntity(
                siteId, user.getId(), educationAgent.getId(), model.getId(), "备课记录"));
        agentConversationRepository.save(new AgentConversationEntity(
                siteId, user.getId(), generalAgent.getId(), model.getId(), "通用对话"));

        mockMvc.perform(get("/api/v1/agents/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("categoryId", educationCategory.getId().toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].agentId").value(educationAgent.getId().intValue()));

        mockMvc.perform(get("/api/v1/agents/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("categoryId", educationCategory.getId().toString())
                        .param("keyword", "备课")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/api/v1/agents/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("categoryId", educationCategory.getId().toString())
                        .param("keyword", "没有匹配")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void publishesAgentApisInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/agents'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/agents'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/agents/mine'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/agents/{reference}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/agents/{agentSn}/chat/completions'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/agents/{agentSn}/chat/stream'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/agents/conversations'].get").exists());
    }

    /**
     * 编辑一个更早的问题：新问题进原问题的版本组，旧问题与旧答案都留着但停用，
     * 历史接口要能给出 1/2。
     */
    @Test
    void editingAQuestionKeepsBothVersionsAndSwitchesThePairedAnswer() throws Exception {
        AgentEntity agent = agentRepository.save(publicAgent("versioned-agent", "Versioned Agent"));
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "version-user", "Version User"));
        String token = accessToken(user.getId());
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", "deepseek-v4.1-flash-0910", "第一版回答", "stop", new LlmUsage(1, 1, 2)));

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/completions", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"原问题\"}]}"))
                .andExpect(status().isOk());

        AgentConversationEntity conversation = agentConversationRepository.findAll().get(0);
        List<AgentChatMessageEntity> firstTurn =
                agentChatMessageRepository.findByAgentConversationIdOrderBySequenceNoAsc(
                        conversation.getId());
        assertEquals(2, firstTurn.size());
        Long originalUserMessageId = firstTurn.get(0).getId();
        Long originalAnswerId = firstTurn.get(1).getId();

        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-2", "deepseek-v4.1-flash-0910", "第二版回答", "stop", new LlmUsage(1, 1, 2)));

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/completions", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"改过的问题"}],
                                 "conversationId":%d,
                                 "editOfMessageId":%d}"""
                                .formatted(conversation.getId(), originalUserMessageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("第二版回答"));

        List<AgentChatMessageEntity> all =
                agentChatMessageRepository.findByAgentConversationIdOrderBySequenceNoAsc(
                        conversation.getId());
        assertEquals(4, all.size());
        AgentChatMessageEntity oldUser =
                agentChatMessageRepository.findById(originalUserMessageId).orElseThrow();
        AgentChatMessageEntity newUser = all.get(2);
        AgentChatMessageEntity oldAnswer =
                agentChatMessageRepository.findById(originalAnswerId).orElseThrow();
        AgentChatMessageEntity newAnswer = all.get(3);

        assertFalse(oldUser.isActive());
        assertEquals(oldUser.getTurnGroupId(), newUser.getTurnGroupId());
        assertEquals(2, newUser.getVersion());
        assertEquals("改过的问题", newUser.getContent());

        assertFalse(oldAnswer.isActive());
        assertEquals(newUser.getId(), newAnswer.getReplyToMessageId());
        assertTrue(newAnswer.isActive());

        mockMvc.perform(get("/api/v1/agents/conversations/{id}/messages", conversation.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].versionCount").value(2))
                .andExpect(jsonPath("$.items[0].active").value(false))
                .andExpect(jsonPath("$.items[2].version").value(2));

        // 切回旧问题：配对答案要跟着回退，不能留下"问题换了、答案还是新的"
        mockMvc.perform(post("/api/v1/agents/conversations/{id}/messages/{mid}/activate",
                        conversation.getId(), originalUserMessageId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        assertTrue(agentChatMessageRepository.findById(originalUserMessageId).orElseThrow().isActive());
        assertFalse(agentChatMessageRepository.findById(newUser.getId()).orElseThrow().isActive());
        assertTrue(agentChatMessageRepository.findById(originalAnswerId).orElseThrow().isActive());
        assertFalse(agentChatMessageRepository.findById(newAnswer.getId()).orElseThrow().isActive());
    }

    /** 重新生成只加答案版本，用户消息不动。 */
    @Test
    void regeneratingAnAnswerAddsASecondVersionInTheSameGroup() throws Exception {
        AgentEntity agent = agentRepository.save(publicAgent("regenerate-agent", "Regenerate Agent"));
        UserAccountEntity user = userAccountRepository.save(
                new UserAccountEntity(siteId, "regen-user", "Regen User"));
        String token = accessToken(user.getId());
        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-1", "deepseek-v4.1-flash-0910", "第一版回答", "stop", new LlmUsage(1, 1, 2)));

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/completions", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"原问题\"}]}"))
                .andExpect(status().isOk());

        AgentConversationEntity conversation = agentConversationRepository.findAll().get(0);
        List<AgentChatMessageEntity> firstTurn =
                agentChatMessageRepository.findByAgentConversationIdOrderBySequenceNoAsc(
                        conversation.getId());
        Long userMessageId = firstTurn.get(0).getId();
        Long originalAnswerId = firstTurn.get(1).getId();

        when(llmClient.complete(any())).thenReturn(new LlmChatCompletion(
                "cmpl-2", "deepseek-v4.1-flash-0910", "第二版回答", "stop", new LlmUsage(1, 1, 2)));

        mockMvc.perform(post("/api/v1/agents/{sn}/chat/completions", agent.getSn())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"原问题"}],
                                 "conversationId":%d,
                                 "regenerateOfMessageId":%d}"""
                                .formatted(conversation.getId(), originalAnswerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("第二版回答"));

        List<AgentChatMessageEntity> all =
                agentChatMessageRepository.findByAgentConversationIdOrderBySequenceNoAsc(
                        conversation.getId());
        assertEquals(3, all.size(), "用户消息不该被复制一份");
        AgentChatMessageEntity oldAnswer =
                agentChatMessageRepository.findById(originalAnswerId).orElseThrow();
        AgentChatMessageEntity newAnswer = all.get(2);

        assertFalse(oldAnswer.isActive());
        assertEquals(oldAnswer.getTurnGroupId(), newAnswer.getTurnGroupId());
        assertEquals(2, newAnswer.getVersion());
        assertEquals(userMessageId, newAnswer.getReplyToMessageId());
        assertTrue(newAnswer.isActive());
    }

    /**
     * 前台用户创建智能体同样受「一个模型」的限制。
     *
     * <p>用户端 DTO 也带 modelIds（走的是同一个 replaceRelations），只是现有页面从不传。
     * 这条用例锁住契约：绕过页面直接调接口，也不能造出多模型智能体。</p>
     */
    @Test
    void rejectsMoreThanOneModelWhenAUserCreatesAnAgent() throws Exception {
        UserAccountEntity carol = userAccountRepository.save(
                new UserAccountEntity(siteId, "agent-carol", "carol"));
        String carolToken = accessToken(carol.getId());

        mockMvc.perform(post("/api/v1/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + carolToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Two Model Agent",
                                  "systemPrompt": "Should be rejected.",
                                  "modelIds": [1, 2],
                                  "defaultModelId": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                // 校验失败走统一处理器：message 固定英文，字段原因在 details 里。
                .andExpect(jsonPath("$.details.modelIds").value(containsString("只能绑定一个对话模型")));
    }

    private AgentEntity publicAgent(String sn, String name) {
        AgentEntity agent = new AgentEntity(siteId, null, sn, sn, name);
        agent.setScope(AgentEntity.SCOPE_PUBLIC);
        agent.setVisibility(AgentEntity.VISIBILITY_PUBLIC);
        agent.setPublishStatus(AgentEntity.PUBLISH_PUBLISHED);
        return agent;
    }
}
