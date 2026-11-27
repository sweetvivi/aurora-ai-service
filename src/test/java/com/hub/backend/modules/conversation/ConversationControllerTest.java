package com.hub.backend.modules.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.infra.llm.dto.LlmUsage;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.chat.dto.ChatArtifactContext;
import com.hub.backend.modules.conversation.entity.ChatMessageEntity;
import com.hub.backend.modules.conversation.entity.ConversationEntity;
import com.hub.backend.modules.conversation.repository.ChatMessageRepository;
import com.hub.backend.modules.conversation.repository.ConversationRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationControllerTest extends AuthenticatedControllerTestSupport {

    private static final String CONVERSATIONS_URL = "/api/v1/conversations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ConversationService conversationService;

    private Long demoUserId;
    private Long otherUserId;
    private Long demoConversationId;
    private Long otherConversationId;
    private String demoToken;
    private String otherToken;

    @BeforeEach
    void seedTwoAccounts() {
        chatMessageRepository.deleteAll();
        conversationRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        demoUserId = userAccountRepository.save(new UserAccountEntity(site.getId(), "demo", "Demo User")).getId();
        otherUserId = userAccountRepository.save(new UserAccountEntity(site.getId(), "other", "Other User")).getId();
        demoToken = accessToken(demoUserId);
        otherToken = accessToken(otherUserId);

        ConversationEntity demoConversation = conversationRepository.save(
                new ConversationEntity(demoUserId, null, "演示会话"));
        otherConversationId = conversationRepository.save(
                new ConversationEntity(otherUserId, null, "别人的会话")).getId();
        demoConversationId = demoConversation.getId();

        for (int sequence = 1; sequence <= 3; sequence++) {
            ChatMessageEntity saved = chatMessageRepository.save(new ChatMessageEntity(
                    demoConversationId,
                    null,
                    sequence,
                    sequence % 2 == 1 ? ChatMessageEntity.ROLE_USER : ChatMessageEntity.ROLE_ASSISTANT,
                    "消息" + sequence,
                    ChatMessageEntity.STATUS_COMPLETED,
                    new LlmUsage(sequence, sequence, sequence * 2),
                    null,
                    null));
            saved.bindAsOwnTurnGroup();
            chatMessageRepository.save(saved);
        }
        demoConversation.registerMessages(3);
        conversationRepository.save(demoConversation);
    }

    @Test
    void listsOnlyTheConversationsOfTheRequestingAccount() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(demoConversationId))
                .andExpect(jsonPath("$.items[0].title").value("演示会话"))
                .andExpect(jsonPath("$.items[0].conversationType").value("CHAT"))
                .andExpect(jsonPath("$.items[0].messageCount").value(3));

        mockMvc.perform(get(CONVERSATIONS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(otherConversationId));
    }

    @Test
    void requiresABearerTokenInsteadOfFallingBackToTheDefaultAccount() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.msg").value("请先登录后再试"));
    }

    @Test
    void hidesAnotherAccountsConversationBehindNotFound() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL + "/" + otherConversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get(CONVERSATIONS_URL + "/" + otherConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(CONVERSATIONS_URL + "/" + otherConversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());

        // the other account's conversation is untouched
        assertEquals(ConversationEntity.STATUS_ACTIVE,
                conversationRepository.findById(otherConversationId).orElseThrow().getStatus());
    }

    @Test
    void pagesMessagesLatestFirstThenOlderWithBefore() throws Exception {
        // Opening a thread returns the newest page, not the oldest.
        String firstPage = mockMvc.perform(get(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].sequenceNo").value(2))
                .andExpect(jsonPath("$.items[1].sequenceNo").value(3))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.hasOlder").value(true))
                .andExpect(jsonPath("$.prevCursor").isNumber())
                .andReturn().getResponse().getContentAsString();

        String before = objectMapper.readTree(firstPage).get("prevCursor").asText();

        mockMvc.perform(get(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .param("size", "2")
                        .param("before", before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.items[0].role").value("user"))
                .andExpect(jsonPath("$.items[0].totalTokens").value(2))
                .andExpect(jsonPath("$.hasOlder").value(false))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void reportsNoMorePagesWhenTheConversationFitsInOnePage() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.hasOlder").value(false));
    }

    @Test
    void renamesAConversation() throws Exception {
        mockMvc.perform(patch(CONVERSATIONS_URL + "/" + demoConversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新的标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新的标题"));

        assertEquals("新的标题", conversationRepository.findById(demoConversationId).orElseThrow().getTitle());
    }

    @Test
    void rejectsABlankTitle() throws Exception {
        mockMvc.perform(patch(CONVERSATIONS_URL + "/" + demoConversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists());
    }

    @Test
    void softDeletesAConversationAndKeepsItsMessages() throws Exception {
        mockMvc.perform(delete(CONVERSATIONS_URL + "/" + demoConversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(CONVERSATIONS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get(CONVERSATIONS_URL + "/" + demoConversationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());

        ConversationEntity stored = conversationRepository.findById(demoConversationId).orElseThrow();
        assertEquals(ConversationEntity.STATUS_DELETED, stored.getStatus());
        assertNotNull(stored.getDeletedAt());
        assertEquals(3, chatMessageRepository.countByConversationId(demoConversationId));
    }

    @Test
    void clampsAnOversizedPageRequest() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(ConversationService.MAX_PAGE_SIZE));
    }

    @Test
    void rejectsAnInvalidAccessToken() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ---------------------------------------------------------------- explicit creation

    @Test
    void createsAnEmptyConversation() throws Exception {
        String body = mockMvc.perform(post(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"杭州旅行计划"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("杭州旅行计划"))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andExpect(jsonPath("$.conversationType").value("CHAT"))
                .andReturn().getResponse().getContentAsString();

        long created = objectMapper.readTree(body).get("id").asLong();
        assertEquals(demoUserId, conversationRepository.findById(created).orElseThrow().getUserId());
    }

    @Test
    void createsAConversationWithoutABody() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void appendsAMessageWithoutInvokingAModel() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"先把这段记下来"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.content").value("先把这段记下来"));

        List<ChatMessageEntity> messages =
                chatMessageRepository.findByConversationIdOrderBySequenceNoAsc(demoConversationId);
        assertEquals(4, messages.size());
        assertEquals("先把这段记下来", messages.get(3).getContent());
        // the conversation counter and derived title follow an appended message
        assertEquals(4, conversationRepository.findById(demoConversationId).orElseThrow().getMessageCount());
    }

    @Test
    void refusesToAppendAMessageToAnotherAccountsConversation() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/" + otherConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"窥探"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnEmptyMessage() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.content").exists());
    }

    @Test
    void recordsAFailedTurnSoHistoryCanBeReopened() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/" + demoConversationId + "/failed-turn")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"画一只猫","errorMessage":"生图失败：额度不足"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(demoConversationId));

        List<ChatMessageEntity> messages =
                chatMessageRepository.findByConversationIdOrderBySequenceNoAsc(demoConversationId);
        assertEquals(5, messages.size());
        assertEquals("user", messages.get(3).getRole());
        assertEquals("画一只猫", messages.get(3).getContent());
        assertEquals("assistant", messages.get(4).getRole());
        assertEquals("FAILED", messages.get(4).getStatus());
        assertEquals("生图失败：额度不足", messages.get(4).getContent());
    }

    // ---------------------------------------------------------------- batch delete

    @Test
    void deletesSeveralConversationsInOneCall() throws Exception {
        Long second = conversationRepository
                .save(new ConversationEntity(demoUserId, null, "第二个会话")).getId();

        mockMvc.perform(post(CONVERSATIONS_URL + "/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":[%d,%d]}""".formatted(demoConversationId, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.deleted").value(2))
                .andExpect(jsonPath("$.skipped").value(0));

        mockMvc.perform(get(CONVERSATIONS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void skipsIdsTheAccountDoesNotOwnWithoutLeakingWhy() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":[%d,%d]}""".formatted(demoConversationId, otherConversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.deleted").value(1))
                // unknown, foreign and already deleted are merged into one number on purpose
                .andExpect(jsonPath("$.skipped").value(1));

        assertEquals("别人的会话",
                conversationRepository.findById(otherConversationId).orElseThrow().getTitle());
    }

    @Test
    void deduplicatesRepeatedIdsInABatch() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":[%d,%d,%d]}"""
                                .formatted(demoConversationId, demoConversationId, demoConversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.deleted").value(1));
    }

    @Test
    void rejectsAnEmptyBatch() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL + "/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.ids").exists());
    }

    @Test
    void rejectsABatchLargerThanTheLimit() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int index = 0; index <= 100; index++) {
            ids.append(index == 0 ? "" : ",").append(index + 1);
        }

        mockMvc.perform(post(CONVERSATIONS_URL + "/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[%s]}".formatted(ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.ids").exists());
    }

    @Test
    void publishesConversationPathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/conversations'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}/messages'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}/messages'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/{id}/messages/{messageId}/activate'].post")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/conversations/batch-delete'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ConversationSummary").exists())
                .andExpect(jsonPath("$.components.schemas.ChatMessagePage").exists());
    }

    @Test
    void regeneratesKeepSiblingVersionsAndActivateSwitchesActive() throws Exception {
        ChatMessageEntity assistant = chatMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(demoConversationId).stream()
                .filter(m -> ChatMessageEntity.ROLE_ASSISTANT.equals(m.getRole()))
                .findFirst()
                .orElseThrow();
        Long turnGroupId = assistant.getTurnGroupId();

        // Soft-hide the first assistant answer, then append a new version in the same group.
        ConversationService.RegeneratePrep prep = conversationService.prepareRegenerate(
                demoConversationId, demoUserId, assistant.getId());
        assertEquals(turnGroupId, prep.turnGroupId());
        ChatMessageEntity firstUser = chatMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(demoConversationId).get(0);
        assertEquals(firstUser.getId(), prep.replyToMessageId());
        ChatMessageEntity v2 = conversationService.appendAssistantMessage(
                demoConversationId, null, "第二版回答", null, new LlmUsage(1, 1, 2), List.of(),
                prep.turnGroupId(), prep.replyToMessageId());

        mockMvc.perform(get(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id==" + assistant.getId() + ")].active").value(false))
                .andExpect(jsonPath("$.items[?(@.id==" + v2.getId() + ")].active").value(true))
                .andExpect(jsonPath("$.items[?(@.id==" + v2.getId() + ")].version").value(2))
                .andExpect(jsonPath("$.items[?(@.id==" + v2.getId() + ")].versionCount").value(2));

        mockMvc.perform(post(CONVERSATIONS_URL + "/" + demoConversationId
                        + "/messages/" + assistant.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assistant.getId()))
                .andExpect(jsonPath("$.active").value(true));

        assertEquals(true, chatMessageRepository.findById(assistant.getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(v2.getId()).orElseThrow().isActive());
    }

    @Test
    void latestArtifactContextIgnoresInactiveNewerMessages() {
        ChatArtifactContext activeContext = new ChatArtifactContext(
                "generate_spreadsheet", "{\"title\":\"当前版本\"}");
        conversationService.appendAssistantMessageWithArtifactContext(
                demoConversationId, null, "当前文件", null, LlmUsage.empty(), List.of(),
                null, null, List.of(activeContext));
        ChatMessageEntity inactiveNewer = conversationService.appendAssistantMessageWithArtifactContext(
                demoConversationId, null, "已停用文件", null, LlmUsage.empty(), List.of(),
                null, null, List.of(new ChatArtifactContext(
                        "generate_spreadsheet", "{\"title\":\"已停用版本\"}")));
        inactiveNewer.deactivate();
        chatMessageRepository.save(inactiveNewer);

        assertEquals(List.of(activeContext),
                conversationService.latestArtifactContext(demoConversationId, demoUserId));
    }

    @Test
    void editSoftHidesSubsequentTurnsAndKeepsUserVersions() {
        List<ChatMessageEntity> original = chatMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(demoConversationId);
        ChatMessageEntity firstUser = original.get(0);
        Long userGroup = firstUser.getTurnGroupId();

        Long group = conversationService.prepareEdit(demoConversationId, demoUserId, firstUser.getId());
        assertEquals(userGroup, group);
        ChatMessageEntity edited = conversationService.appendUserMessageVersion(
                demoConversationId, group, "改过的提问", List.of());
        conversationService.appendAssistantMessage(
                demoConversationId, null, "新回答", null, new LlmUsage(1, 1, 2), List.of(), null,
                edited.getId());

        assertEquals(false, chatMessageRepository.findById(firstUser.getId()).orElseThrow().isActive());
        assertEquals(true, edited.isActive());
        assertEquals(2, edited.getVersion());
        // Original assistant (seq 2) and second user (seq 3) stay soft-hidden.
        assertEquals(false, chatMessageRepository.findById(original.get(1).getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(original.get(2).getId()).orElseThrow().isActive());
    }

    @Test
    void activatingEditedUserVersionSwitchesPairedAssistantAndHidesFollowUps() throws Exception {
        List<ChatMessageEntity> original = chatMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(demoConversationId);
        ChatMessageEntity firstUser = original.get(0);
        ChatMessageEntity firstAssistant = original.get(1);
        firstAssistant.bindReplyTo(firstUser.getId());
        chatMessageRepository.save(firstAssistant);

        Long group = conversationService.prepareEdit(demoConversationId, demoUserId, firstUser.getId());
        ChatMessageEntity editedUser = conversationService.appendUserMessageVersion(
                demoConversationId, group, "改过的提问", List.of());
        ChatMessageEntity newAssistant = conversationService.appendAssistantMessage(
                demoConversationId, null, "新回答", null, new LlmUsage(1, 1, 2), List.of(), null,
                editedUser.getId());
        ChatMessageEntity followUpUser = conversationService.appendUserMessage(
                demoConversationId, "追问一句", List.of());
        ChatMessageEntity followUpAssistant = conversationService.appendAssistantMessage(
                demoConversationId, null, "追问回答", null, new LlmUsage(1, 1, 2), List.of(), null,
                followUpUser.getId());

        mockMvc.perform(post(CONVERSATIONS_URL + "/" + demoConversationId
                        + "/messages/" + firstUser.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstUser.getId()))
                .andExpect(jsonPath("$.active").value(true));

        assertEquals(true, chatMessageRepository.findById(firstUser.getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(editedUser.getId()).orElseThrow().isActive());
        assertEquals(true, chatMessageRepository.findById(firstAssistant.getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(newAssistant.getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(followUpUser.getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(followUpAssistant.getId()).orElseThrow().isActive());

        mockMvc.perform(post(CONVERSATIONS_URL + "/" + demoConversationId
                        + "/messages/" + editedUser.getId() + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk());

        assertEquals(false, chatMessageRepository.findById(firstUser.getId()).orElseThrow().isActive());
        assertEquals(true, chatMessageRepository.findById(editedUser.getId()).orElseThrow().isActive());
        assertEquals(false, chatMessageRepository.findById(firstAssistant.getId()).orElseThrow().isActive());
        assertEquals(true, chatMessageRepository.findById(newAssistant.getId()).orElseThrow().isActive());
        assertEquals(true, chatMessageRepository.findById(followUpUser.getId()).orElseThrow().isActive());
        assertEquals(true, chatMessageRepository.findById(followUpAssistant.getId()).orElseThrow().isActive());
    }

    @Test
    void prepareRegenerateRejectsUserMessageIds() {
        ChatMessageEntity firstUser = chatMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(demoConversationId).get(0);

        assertThrows(InvalidRequestException.class,
                () -> conversationService.prepareRegenerate(
                        demoConversationId, demoUserId, firstUser.getId()));
    }

    @Test
    void prepareEditRejectsAssistantMessageIds() {
        ChatMessageEntity assistant = chatMessageRepository
                .findByConversationIdOrderBySequenceNoAsc(demoConversationId).stream()
                .filter(m -> ChatMessageEntity.ROLE_ASSISTANT.equals(m.getRole()))
                .findFirst()
                .orElseThrow();

        assertThrows(InvalidRequestException.class,
                () -> conversationService.prepareEdit(
                        demoConversationId, demoUserId, assistant.getId()));
    }

    @Test
    void messagePageExposesVersionFieldsForTheActiveTimeline() throws Exception {
        mockMvc.perform(get(CONVERSATIONS_URL + "/" + demoConversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].turnGroupId").isNumber())
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.items[0].versionCount").value(1));
    }

    // ---------------------------------------------------------------- 会话类型隔离

    /**
     * 绘画把每张图存成一个会话（标题 `AI绘画：{prompt}`）。它和模型对话共表，
     * 所以类型必须落库，否则这些「作品容器」会混进聊天的对话记录。
     */
    @Test
    void createsAPaintingThreadThatStaysOutOfTheChatHistory() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"AI绘画：一只戴帽子的猫\",\"conversationType\":\"PAINTING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationType").value("PAINTING"));

        // 聊天侧只查 CHAT：仍是种子里那一条，绘画会话不出现
        mockMvc.perform(get(CONVERSATIONS_URL + "?type=CHAT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(demoConversationId))
                .andExpect(jsonPath("$.items[0].conversationType").value("CHAT"));

        // 绘画侧只查 PAINTING：正好是刚建的那条
        mockMvc.perform(get(CONVERSATIONS_URL + "?type=PAINTING")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("AI绘画：一只戴帽子的猫"));
    }

    @Test
    void typeFilterIsCaseInsensitiveAndDefaultsToAllWhenAbsent() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"AI绘画：小写类型\",\"conversationType\":\"painting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationType").value("PAINTING"));

        // 不带类型 → 全部（管理端/分享等场景仍能看到绘画会话）
        mockMvc.perform(get(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    /**
     * 视频工作台早期版本同样把每次生成存成一个会话（标题 `AI视频：{prompt}`）。
     * V95 把存量行标成 VIDEO —— 它不是 CHAT，所以不会出现在聊天的对话记录里。
     */
    @Test
    void legacyVideoThreadsStayOutOfTheChatHistory() throws Exception {
        conversationRepository.save(new ConversationEntity(
                demoUserId, null, "AI视频：a cat walking on the beach", ConversationEntity.TYPE_VIDEO));

        mockMvc.perform(get(CONVERSATIONS_URL + "?type=CHAT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(demoConversationId));

        // 不过滤时仍在（管理端按 id 还能访问，行没被删）
        mockMvc.perform(get(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void rejectsAnUnknownConversationType() throws Exception {
        mockMvc.perform(post(CONVERSATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"未知类型\",\"conversationType\":\"AGENT\"}"))
                .andExpect(status().isBadRequest());
    }
}
