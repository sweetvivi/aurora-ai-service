package com.hub.backend.modules.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.PaymentRequiredException;
import com.hub.backend.infra.llm.LlmClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.infra.llm.dto.LlmChatCommand;
import com.hub.backend.infra.llm.dto.LlmChatCompletion;
import com.hub.backend.infra.llm.dto.LlmStreamChunk;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.conversation.entity.ChatMessageEntity;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ChatMessageRepository;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest extends AuthenticatedControllerTestSupport {

    private static final String COMPLETIONS_URL = "/api/v1/chat/completions";
    private static final String STREAM_URL = "/api/v1/chat/stream";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private UserRelayCredentialResolver relayCredentials;

    @Autowired
    private ChatRequestRegistry requestRegistry;

    private Long siteId;
    private String callerToken;

    @BeforeEach
    void seedCatalogue() {
        chatMessageRepository.deleteAll();
        conversationRepository.deleteAll();
        userAccountRepository.deleteAll();
        llmModelRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        siteId = site.getId();

        LlmModelEntity model = new LlmModelEntity(
                "deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "DeepSeek", "deepseek-v4.1-flash");
        model.setSiteId(site.getId());
        model.setSortOrder(1);
        llmModelRepository.save(model);

        Long callerId = userAccountRepository.save(
                new UserAccountEntity(site.getId(), "demo", "Demo User")).getId();
        callerToken = accessToken(callerId);
        when(relayCredentials.resolve(any(), any())).thenReturn(new ProviderCredentials(
                "OPENAI_COMPATIBLE",
                "http://new-api.test/v1",
                "sk-user-relay",
                null,
                null,
                null));
    }

    /**
     * Accounts are created per test rather than seeded globally: several tests assert on the demo
     * account that the resolver creates on demand, and a pre-seeded user would break that count.
     */
    private Long accountId(String username) {
        return userAccountRepository.findFirstBySiteIdAndUsername(siteId, username)
                .orElseGet(() -> userAccountRepository.save(
                        new UserAccountEntity(siteId, username, username)))
                .getId();
    }

    @Test
    void completesChatAndPersistsTheExchange() throws Exception {
        stubCompletion("pong", new LlmUsage(10, 2, 12));

        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"say pong\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("pong"))
                .andExpect(jsonPath("$.model").value("deepseek-v4.1-flash"))
                .andExpect(jsonPath("$.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.promptTokens").value(10))
                .andExpect(jsonPath("$.usage.completionTokens").value(2))
                .andExpect(jsonPath("$.usage.totalTokens").value(12))
                .andExpect(jsonPath("$.conversationId").isNumber())
                .andExpect(jsonPath("$.messageId").isNumber())
                .andExpect(jsonPath("$.latencyMs").isNumber());

        List<ChatMessageEntity> messages = chatMessageRepository.findAll(Sort.by("sequenceNo").ascending());
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("say pong", messages.get(0).getContent());
        assertEquals(1, messages.get(0).getSequenceNo());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("pong", messages.get(1).getContent());
        assertEquals(2, messages.get(1).getSequenceNo());
        assertEquals(12, messages.get(1).getTotalTokens().intValue());

        ConversationEntity conversation = onlyConversation();
        assertEquals("say pong", conversation.getTitle());
        assertEquals(2, conversation.getMessageCount());
        assertEquals("CHAT", conversation.getConversationType());

        // the authenticated caller owns the conversation
        assertEquals(1, userAccountRepository.count());
        assertEquals(userAccountRepository.findFirstBySiteIdAndUsername(siteId, "demo")
                .orElseThrow().getId(), conversation.getUserId());
    }

    @Test
    void forwardsTheCatalogueRouteToTheGateway() throws Exception {
        appSiteRepository.findAll().forEach(site -> {
            LlmModelEntity alias = new LlmModelEntity("pro-alias", "Pro Alias", "DeepSeek", "deepseek-v4-pro-0813");
            alias.setSiteId(site.getId());
            llmModelRepository.save(alias);
        });
        stubCompletion("ok", LlmUsage.empty());

        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"pro-alias","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("pro-alias"));

        ArgumentCaptor<LlmChatCommand> command = ArgumentCaptor.forClass(LlmChatCommand.class);
        verify(llmClient).complete(command.capture());
        assertEquals("deepseek-v4-pro-0813", command.getValue().model());
    }

    @Test
    void appendsFurtherTurnsToTheSameConversation() throws Exception {
        stubCompletion("first", LlmUsage.empty());

        String firstBody = mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"one\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(firstBody).get("conversationId").asLong();

        stubCompletion("second", LlmUsage.empty());
        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":%d,"messages":[{"role":"user","content":"two"}]}"""
                                .formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value((int) conversationId))
                .andExpect(jsonPath("$.content").value("second"));

        assertEquals(1, conversationRepository.count());
        assertEquals(4, chatMessageRepository.countByConversationId(conversationId));
        assertEquals(4, onlyConversation().getMessageCount());
        assertEquals("one", onlyConversation().getTitle());
    }

    @Test
    void rejectsRequestWithoutMessages() throws Exception {
        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.messages").exists());

        assertEquals(0, conversationRepository.count());
    }

    @Test
    void rejectsRequestWithoutAUserTurn() throws Exception {
        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请求参数有误")));
    }

    @Test
    void mapsGatewayFailureToBadGatewayAndStoresAFailedTurn() throws Exception {
        doThrow(new LlmInvocationException("Model gateway returned HTTP 502: upstream error"))
                .when(llmClient).complete(any());

        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("发生错误，请稍后重试"));

        List<ChatMessageEntity> messages = chatMessageRepository.findAll(Sort.by("sequenceNo").ascending());
        assertEquals(2, messages.size());
        assertEquals("FAILED", messages.get(1).getStatus());
        assertEquals("LLM_INVOCATION_FAILED", messages.get(1).getErrorCode());
        assertNotNull(messages.get(1).getContent());
        assertFalse(messages.get(1).getContent().isBlank());
    }

    @Test
    void rejectsBothChatEndpointsBeforePersistenceWhenTheUserBalanceIsZero() throws Exception {
        doThrow(new PaymentRequiredException("Insufficient account balance"))
                .when(relayCredentials).resolve(any(), any());
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        mockMvc.perform(post(COMPLETIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        mockMvc.perform(post(STREAM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402));

        assertEquals(0, conversationRepository.count());
        assertEquals(0, chatMessageRepository.count());
        verifyNoInteractions(llmClient);
    }

    @Test
    void streamsServerSentEvents() throws Exception {
        doAnswer(invocation -> {
            Consumer<LlmStreamChunk> consumer = invocation.getArgument(1);
            consumer.accept(new LlmStreamChunk("s1", "Hel", null, null, false));
            consumer.accept(new LlmStreamChunk(
                    "s1", "lo", null, "stop", false, new LlmUsage(10, 2, 12)));
            consumer.accept(LlmStreamChunk.done("s1"));
            return null;
        }).when(llmClient).stream(any(), any());

        MvcResult result = mockMvc.perform(post(STREAM_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event:message.created"), body);
        assertTrue(body.contains("\"requestId\""), body);
        assertTrue(body.contains("event:message.delta"), body);
        assertTrue(body.contains("event:message.completed"), body);
        assertTrue(body.contains("\"content\":\"Hel\""), body);
        assertTrue(body.contains("\"content\":\"Hello\""), body);

        List<ChatMessageEntity> messages = chatMessageRepository.findAll(Sort.by("sequenceNo").ascending());
        assertEquals(2, messages.size());
        assertEquals("Hello", messages.get(1).getContent());
        assertEquals(12, messages.get(1).getTotalTokens().intValue());
    }

    @Test
    void publishesChatPathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/chat/completions'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/chat/stream'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/chat/requests/{requestId}/cancel'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ChatCompletionRequest").exists())
                .andExpect(jsonPath("$.components.schemas.ChatCompletionResponse").exists());
    }

    @Test
    void stopsAnInFlightGenerationOfTheCallingAccount() throws Exception {
        Long caller = accountId("alice");
        String token = accessToken(caller);
        ChatRequestRegistry.Entry entry = requestRegistry.register(caller, "req-1");

        mockMvc.perform(post("/api/v1/chat/requests/req-1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        assertTrue(entry.isCancelled(), "the streaming loop should see the stop signal");
    }

    @Test
    void stoppingAnAlreadyFinishedGenerationIsIdempotent() throws Exception {
        Long caller = accountId("alice");
        String token = accessToken(caller);
        requestRegistry.register(caller, "req-1");
        requestRegistry.finish(caller, "req-1", ChatRequestRegistry.OUTCOME_COMPLETED);

        // pressing stop just as the answer completes must not surface an error
        mockMvc.perform(post("/api/v1/chat/requests/req-1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void refusesToStopAGenerationOfAnotherAccount() throws Exception {
        Long owner = accountId("alice");
        Long intruder = accountId("bob");
        String intruderToken = accessToken(intruder);
        ChatRequestRegistry.Entry entry = requestRegistry.register(owner, "req-1");

        mockMvc.perform(post("/api/v1/chat/requests/req-1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertFalse(entry.isCancelled(), "another account must not be able to stop this generation");
    }

    @Test
    void reportsAnUnknownRequestAsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/chat/requests/ghost/cancel")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken(accountId("alice"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("内容不存在")));
    }

    private void stubCompletion(String content, LlmUsage usage) {
        when(llmClient.complete(any())).thenReturn(
                new LlmChatCompletion("cmpl-1", "deepseek-v4.1-flash", content, "stop", usage));
    }

    private ConversationEntity onlyConversation() {
        List<ConversationEntity> all = conversationRepository.findAll();
        assertEquals(1, all.size());
        return all.get(0);
    }
}
