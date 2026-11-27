package com.hub.backend.modules.smartedit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.smartedit.entity.SmartEditRecordEntity;
import com.hub.backend.modules.smartedit.repository.SmartEditRecordRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The smart-editing board contract: {@code /api/v1/smart-editing/records} and {@code /demos}.
 *
 * <p>Records are the one place in this module where state outlives a request, so the tests care
 * mostly about ownership and about canvas/messages surviving the JSON round trip.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmartEditRecordControllerTest extends AuthenticatedControllerTestSupport {

    private static final String RECORDS_URL = "/api/v1/smart-editing/records";
    private static final String DEMOS_URL = "/api/v1/smart-editing/demos";
    private static final String EMPTY_CANVAS = "{\"images\":[],\"scale\":1}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private SmartEditRecordRepository recordRepository;

    private Long demoUserId;
    private Long otherUserId;
    private String demoToken;
    private String otherToken;

    @BeforeEach
    void seedTwoAccounts() {
        recordRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        demoUserId = userAccountRepository
                .save(new UserAccountEntity(site.getId(), "demo", "Demo User")).getId();
        otherUserId = userAccountRepository
                .save(new UserAccountEntity(site.getId(), "other", "Other User")).getId();
        demoToken = accessToken(demoUserId);
        otherToken = accessToken(otherUserId);
    }

    private Long saveRecord(Long userId, String title) {
        return recordRepository.save(new SmartEditRecordEntity(
                userId, title, EMPTY_CANVAS, "[]", "", SmartEditRecordEntity.STATUS_ACTIVE)).getId();
    }

    // ---------------------------------------------------------------- listing

    @Test
    void listsOnlyTheRecordsOfTheRequestingAccount() throws Exception {
        saveRecord(demoUserId, "我的画板");
        saveRecord(otherUserId, "别人的画板");

        mockMvc.perform(get(RECORDS_URL).header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("我的画板"))
                .andExpect(jsonPath("$.items[0].canvas.images").isArray())
                .andExpect(jsonPath("$.items[0].messages").isArray())
                .andExpect(jsonPath("$.items[0].cover").value(""))
                .andExpect(jsonPath("$.items[0].createTime").exists())
                .andExpect(jsonPath("$.items[0].updateTime").exists());

        mockMvc.perform(get(RECORDS_URL).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("别人的画板"));
    }

    @Test
    void listsTheMostRecentlyUpdatedRecordFirst() throws Exception {
        saveRecord(demoUserId, "第一个");
        saveRecord(demoUserId, "第二个");
        Long latest = saveRecord(demoUserId, "第三个");

        mockMvc.perform(get(RECORDS_URL).header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(latest))
                .andExpect(jsonPath("$.items[0].title").value("第三个"));
    }

    @Test
    void pagesWithAPageNumberStartingAtOne() throws Exception {
        for (int index = 1; index <= 5; index++) {
            saveRecord(demoUserId, "画板 " + index);
        }

        mockMvc.perform(get(RECORDS_URL).header("Authorization", "Bearer " + demoToken)
                        .param("page", "2").param("rows", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items", hasSize(2)));

        mockMvc.perform(get(RECORDS_URL).header("Authorization", "Bearer " + demoToken)
                        .param("page", "0").param("rows", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void clampsAnOversizedPageToHundredRows() throws Exception {
        List<SmartEditRecordEntity> records = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            records.add(new SmartEditRecordEntity(
                    demoUserId, "画板 " + index, EMPTY_CANVAS, "[]", "",
                    SmartEditRecordEntity.STATUS_ACTIVE));
        }
        recordRepository.saveAll(records);

        mockMvc.perform(get(RECORDS_URL)
                        .header("Authorization", "Bearer " + demoToken)
                        .param("rows", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(101))
                .andExpect(jsonPath("$.items", hasSize(100)));
    }

    // ---------------------------------------------------------------- creation

    @Test
    void createsAnEmptyBoardWhenNoBodyIsSent() throws Exception {
        mockMvc.perform(post(RECORDS_URL).header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("未命名画板"))
                .andExpect(jsonPath("$.canvas.images", hasSize(0)))
                .andExpect(jsonPath("$.canvas.scale").value(1))
                .andExpect(jsonPath("$.messages", hasSize(0)))
                .andExpect(jsonPath("$.cover").value(""));
    }

    @Test
    void keepsTheCanvasMessagesAndCoverThatWereSent() throws Exception {
        String body = mockMvc.perform(post(RECORDS_URL)
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"商品图精修",
                                 "canvas":{"images":[{"id":"img_1","url":"/api/v1/files/7/content","x":0,"y":0,"width":10,"height":10,"scaleX":1,"scaleY":1,"rotation":0}],"scale":1.5},
                                 "messages":[{"id":"msg_1","role":"user","content":"把背景换成纯色","timestamp":1730000000000}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("商品图精修"))
                .andExpect(jsonPath("$.canvas.scale").value(1.5))
                .andExpect(jsonPath("$.canvas.images", hasSize(1)))
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.cover").value("/api/v1/files/7/content"))
                .andReturn().getResponse().getContentAsString();

        long created = objectMapper.readTree(body).get("id").asLong();
        assertEquals(demoUserId, recordRepository.findById(created).orElseThrow().getUserId());
    }

    @Test
    void rejectsAMessagesDocumentThatIsNotAnArray() throws Exception {
        mockMvc.perform(post(RECORDS_URL)
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":{"role":"user"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ---------------------------------------------------------------- detail

    @Test
    void returnsTheCanvasAndMessagesInTheDetail() throws Exception {
        Long id = recordRepository.save(new SmartEditRecordEntity(
                demoUserId,
                "详情",
                "{\"images\":[{\"id\":\"img_1\",\"url\":\"/a.png\"}],\"scale\":2}",
                "[{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":\"已完成\",\"timestamp\":1}]",
                "/a.png",
                SmartEditRecordEntity.STATUS_ACTIVE)).getId();

        mockMvc.perform(get(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.canvas.scale").value(2))
                .andExpect(jsonPath("$.canvas.images", hasSize(1)))
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].content").value("已完成"))
                .andExpect(jsonPath("$.cover").value("/a.png"));
    }

    @Test
    void hidesAnotherAccountsRecordBehindNotFound() throws Exception {
        Long foreign = saveRecord(otherUserId, "别人的画板");

        mockMvc.perform(get(RECORDS_URL + "/" + foreign)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(put(RECORDS_URL + "/" + foreign + "/canvas")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canvas\":{\"images\":[]}}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(RECORDS_URL + "/" + foreign)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isNotFound());

        assertEquals(SmartEditRecordEntity.STATUS_ACTIVE,
                recordRepository.findById(foreign).orElseThrow().getStatus());
    }

    // ---------------------------------------------------------------- canvas / delete

    @Test
    void overwritesTheCanvasAndRefreshesTheCover() throws Exception {
        Long id = saveRecord(demoUserId, "自动保存");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/canvas")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"canvas":{"images":[
                                    {"id":"img_1","url":"/api/v1/files/11/content"},
                                    {"id":"img_2","url":"/api/v1/files/12/content"}],
                                  "scale":1.75,
                                  "stagePos":{"x":10,"y":20}}}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.images", hasSize(2)))
                .andExpect(jsonPath("$.canvas.scale").value(1.75))
                .andExpect(jsonPath("$.canvas.stagePos.x").value(10))
                .andExpect(jsonPath("$.cover").value("/api/v1/files/11/content"));
    }

    @Test
    void rejectsACanvasThatIsNotAJsonObject() throws Exception {
        Long id = saveRecord(demoUserId, "非法画布");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/canvas")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canvas\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("canvas")));
    }

    @Test
    void rejectsARequestWithoutACanvas() throws Exception {
        Long id = saveRecord(demoUserId, "缺画布");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/canvas")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.canvas").exists());
    }

    @Test
    void overwritesTheCompleteMessageArray() throws Exception {
        Long id = saveRecord(demoUserId, "消息保存");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/messages")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[
                                    {"id":"msg_1","role":"user","content":"把背景换成纯蓝色","timestamp":1730000000000},
                                    {"id":"msg_2","role":"assistant","content":"已生成","timestamp":1730000000001}]}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.messages[0].id").value("msg_1"))
                .andExpect(jsonPath("$.messages[1].content").value("已生成"));
    }

    @Test
    void replacesExistingMessagesInsteadOfAppendingThem() throws Exception {
        Long id = recordRepository.save(new SmartEditRecordEntity(
                demoUserId,
                "覆盖消息",
                EMPTY_CANVAS,
                "[{\"id\":\"old\",\"role\":\"user\",\"content\":\"旧消息\",\"timestamp\":1}]",
                "",
                SmartEditRecordEntity.STATUS_ACTIVE)).getId();

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/messages")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(0)));
    }

    @Test
    void rejectsMessagesThatAreNotAJsonArray() throws Exception {
        Long id = saveRecord(demoUserId, "非法消息");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/messages")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":{\"role\":\"user\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("messages")));
    }

    @Test
    void rejectsARequestWithoutMessages() throws Exception {
        Long id = saveRecord(demoUserId, "缺消息");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/messages")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.messages").exists());
    }

    @Test
    void hidesAnotherAccountsMessagesBehindNotFound() throws Exception {
        Long foreign = saveRecord(otherUserId, "别人的消息");

        mockMvc.perform(put(RECORDS_URL + "/" + foreign + "/messages")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- rename

    @Test
    void renamesAnOwnedRecordAndTrimsTheTitle() throws Exception {
        Long id = saveRecord(demoUserId, "旧名称");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/title")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  新的项目名称  "}"""))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新的项目名称"));
    }

    @Test
    void rejectsABlankRecordTitle() throws Exception {
        Long id = saveRecord(demoUserId, "旧名称");

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/title")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists());

        assertEquals("旧名称", recordRepository.findById(id).orElseThrow().getTitle());
    }

    @Test
    void rejectsARecordTitleLongerThanTwoHundredAndFiftyFiveCharacters() throws Exception {
        Long id = saveRecord(demoUserId, "旧名称");
        String title = "a".repeat(256);

        mockMvc.perform(put(RECORDS_URL + "/" + id + "/title")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("title", title))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists());

        assertEquals("旧名称", recordRepository.findById(id).orElseThrow().getTitle());
    }

    @Test
    void hidesAnotherAccountsRenameBehindNotFound() throws Exception {
        Long foreign = saveRecord(otherUserId, "别人的名称");

        mockMvc.perform(put(RECORDS_URL + "/" + foreign + "/title")
                        .header("Authorization", "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"越权修改"}"""))
                .andExpect(status().isNotFound());

        assertEquals("别人的名称", recordRepository.findById(foreign).orElseThrow().getTitle());
    }

    @Test
    void deletesARecord() throws Exception {
        Long id = saveRecord(demoUserId, "待删除");

        mockMvc.perform(delete(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(RECORDS_URL + "/" + id)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isNotFound());

        assertTrue(recordRepository.findById(id).isEmpty());
    }

    // ---------------------------------------------------------------- demos

    @Test
    void servesPublicDemosWithoutAnAccountAndHidesThemFromTheRecordList() throws Exception {
        recordRepository.save(new SmartEditRecordEntity(
                null, "商品图精修示例", EMPTY_CANVAS,
                "[{\"id\":\"msg_1\",\"role\":\"user\",\"content\":\"抠图\",\"timestamp\":1}]",
                "", SmartEditRecordEntity.STATUS_DEMO));

        mockMvc.perform(get(DEMOS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("商品图精修示例"))
                .andExpect(jsonPath("$[0].messages", hasSize(1)));

        mockMvc.perform(get(RECORDS_URL).header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    // ---------------------------------------------------------------- openapi

    @Test
    void publishesTheSmartEditingPathsInTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records/{id}/canvas'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records/{id}/messages'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/records/{id}/title'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/smart-editing/demos'].get").exists())
                .andExpect(jsonPath("$.components.schemas.SmartEditRecord").exists())
                .andExpect(jsonPath("$.components.schemas.SmartEditRecordPage").exists());
    }
}
