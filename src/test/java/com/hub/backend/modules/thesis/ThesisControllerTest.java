package com.hub.backend.modules.thesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.thesis.repository.ThesisDocumentRepository;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end coverage of the thesis module over HTTP: the free outline step, saving it, editing the
 * tree, ownership isolation and soft delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThesisControllerTest extends AuthenticatedControllerTestSupport {

    private static final String BASE = "/api/v1/thesis";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppSiteRepository appSiteRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ThesisDocumentRepository thesisDocumentRepository;

    private String demoToken;
    private String otherToken;

    @BeforeEach
    void seedTwoAccounts() {
        thesisDocumentRepository.deleteAll();
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("default", "Default Site"));
        Long demoUserId = userAccountRepository.save(new UserAccountEntity(site.getId(), "demo", "Demo")).getId();
        Long otherUserId = userAccountRepository.save(new UserAccountEntity(site.getId(), "other", "Other")).getId();
        demoToken = accessToken(demoUserId);
        otherToken = accessToken(otherUserId);
    }

    private String outlineRequest() {
        return objectMapper.valueToTree(Map.of(
                "topic", "基于大语言模型的金融舆情分析系统",
                "educationLevel", "本科在读",
                "paperType", "论文助手",
                "outlineDepth", 3,
                "discipline", "工学",
                "major", "计算机科学与技术",
                "targetWords", 8000,
                "referenceEnabled", true,
                "attachmentsEnabled", true,
                "dataModelEnabled", false)).toPrettyString();
    }

    @Test
    void catalogIsPublicAndCarriesEveryWorkbenchOption() throws Exception {
        mockMvc.perform(get(BASE + "/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.educations", hasSize(8)))
                .andExpect(jsonPath("$.outlineDepths", hasSize(2)))
                .andExpect(jsonPath("$.paperTypes", hasSize(13)))
                .andExpect(jsonPath("$.wordOptions", hasSize(8)))
                .andExpect(jsonPath("$.disciplines[0].majors[0]").value("计算机科学与技术"))
                .andExpect(jsonPath("$.educations[4].label").value("硕士在读"))
                .andExpect(jsonPath("$.educations[4].tier").value("MASTER"))
                .andExpect(jsonPath("$.outlineDepths[0].value").value(2));
    }

    @Test
    void topicOptimisationNeedsNoToken() throws Exception {
        mockMvc.perform(post(BASE + "/outlines/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("topic", "短视频推荐算法优化", "major", "软件工程"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimized", notNullValue()))
                .andExpect(jsonPath("$.reason", notNullValue()));
    }

    @Test
    void freeOutlineIsPublicAndDoesNotPersist() throws Exception {
        mockMvc.perform(post(BASE + "/outlines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outlineRequest()))
                .andExpect(status().isOk())
                // 响应是对象：outline + 实际使用的引擎
                .andExpect(jsonPath("$.mode").value("TEMPLATE"))
                .andExpect(jsonPath("$.outline", hasSize(7)))
                .andExpect(jsonPath("$.outline[0].level").value(1))
                .andExpect(jsonPath("$.outline[0].children[0].level").value(2));

        org.assertj.core.api.Assertions.assertThat(thesisDocumentRepository.count()).isZero();
    }

    @Test
    void paperTypeDecidesTheSkeletonEvenWhenTheEducationLevelSaysUndergraduate() throws Exception {
        // 「教学设计」是教案体例，不该因为选了「本科在读」就变成系统设计与实现；
        // 「开题报告」同理要走开题分支，而不是被学历层次覆盖成本科论文。
        String design = outlineTitles("教学设计", "本科在读");
        String proposal = outlineTitles("开题报告", "本科在读");
        String thesis = outlineTitles("论文助手", "本科在读");

        org.assertj.core.api.Assertions.assertThat(design).contains("教学目标与重难点");
        org.assertj.core.api.Assertions.assertThat(proposal).contains("研究内容与拟解决的关键问题");
        org.assertj.core.api.Assertions.assertThat(thesis).contains("系统设计与实现验证");
        org.assertj.core.api.Assertions.assertThat(design).isNotEqualTo(thesis);
    }

    /** 返回大纲里的全部标题，拼成一串便于断言章节名。 */
    private String outlineTitles(String paperType, String educationLevel) throws Exception {
        String body = objectMapper.valueToTree(Map.of(
                "topic", "在线教学平台设计",
                "educationLevel", educationLevel,
                "paperType", paperType,
                "outlineDepth", 2,
                "discipline", "工学",
                "major", "软件工程",
                "targetWords", 5000)).toPrettyString();

        String json = mockMvc.perform(post(BASE + "/outlines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        StringBuilder titles = new StringBuilder();
        for (var node : objectMapper.readTree(json).path("outline")) {
            titles.append(node.path("title").asText()).append('|');
        }
        return titles.toString();
    }

    @Test
    void twoLevelOutlineHasNoThirdLevel() throws Exception {
        String body = objectMapper.valueToTree(Map.of(
                "topic", "选题",
                "educationLevel", "专科在读",
                "outlineDepth", 2,
                "discipline", "工学",
                "major", "软件工程",
                "targetWords", 5000)).toPrettyString();

        mockMvc.perform(post(BASE + "/outlines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outline[0].level").value(1))
                .andExpect(jsonPath("$.outline[0].children[0].level").value(2))
                .andExpect(jsonPath("$.outline[0].children[0].children", hasSize(0)));
    }

    @Test
    void rejectsAnOutOfRangeDepth() throws Exception {
        String body = objectMapper.valueToTree(Map.of(
                "topic", "选题",
                "educationLevel", "本科在读",
                "outlineDepth", 5,
                "discipline", "工学",
                "major", "软件工程",
                "targetWords", 8000)).toPrettyString();

        mockMvc.perform(post(BASE + "/outlines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modelModeFallsBackToTemplateWhenNoUpstreamIsConfigured() throws Exception {
        // 测试环境没有上游 key：请求模型时必须 200 + TEMPLATE，而不是 503 或 500。
        // 这条是免费功能可用性的底线 —— 上游不可用不应该让「生成大纲」按钮变成错误。
        String body = objectMapper.valueToTree(Map.of(
                "topic", "模型回落测试",
                "educationLevel", "本科在读",
                "outlineDepth", 3,
                "discipline", "工学",
                "major", "软件工程",
                "targetWords", 8000,
                "useModel", true,
                "model", "deepseek-v4.1-flash")).toPrettyString();

        mockMvc.perform(post(BASE + "/outlines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TEMPLATE"))
                .andExpect(jsonPath("$.outline", hasSize(7)));
    }

    @Test
    void catalogReportsWhetherAModelIsReachable() throws Exception {
        mockMvc.perform(get(BASE + "/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelEnabled").isBoolean())
                .andExpect(jsonPath("$.models").isArray());
    }

    @Test
    void savingAnOutlineRequiresAToken() throws Exception {
        mockMvc.perform(post(BASE + "/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outlineRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void saveReadEditAndDeleteADraft() throws Exception {
        String created = mockMvc.perform(post(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outlineRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUTLINE"))
                .andExpect(jsonPath("$.outlineVersion").value(1))
                .andExpect(jsonPath("$.educationLevel").value("本科在读"))
                .andExpect(jsonPath("$.outlineDepth").value(3))
                .andExpect(jsonPath("$.nodeCount", greaterThan(40)))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));

        // 整树覆盖：改名 + 只保留一章
        String update = objectMapper.writeValueAsString(Map.of(
                "outline", List.of(Map.of(
                        "title", "第一章 绪论（已改名）",
                        "level", 1,
                        "children", List.of(Map.of("title", "1.1 研究背景", "level", 2)))))
        );
        mockMvc.perform(put(BASE + "/documents/" + id + "/outline")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outlineVersion").value(2))
                .andExpect(jsonPath("$.nodeCount").value(2))
                .andExpect(jsonPath("$.outline[0].title").value("第一章 绪论（已改名）"));

        // 另一个账号拿不到这份稿件，也不会知道它存在
        mockMvc.perform(get(BASE + "/documents/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(BASE + "/documents/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNoContent());

        // 软删除后列表里也必须消失，否则「我的稿件」会留一条点不开的记录
        mockMvc.perform(get(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get(BASE + "/documents/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void dataModelAppendixSitsBeforeTheReferences() throws Exception {
        String body = objectMapper.valueToTree(Map.of(
                "topic", "基于深度学习的短视频推荐算法优化",
                "educationLevel", "本科在读",
                "paperType", "论文助手",
                "outlineDepth", 3,
                "discipline", "工学",
                "major", "计算机科学与技术",
                "targetWords", 8000,
                "dataModelEnabled", true)).toPrettyString();

        mockMvc.perform(post(BASE + "/outlines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // 五章正文 + 附录 + 参考文献 + 致谢
                .andExpect(jsonPath("$.outline", hasSize(8)))
                .andExpect(jsonPath("$.outline[5].title").value(
                        org.hamcrest.Matchers.containsString("数据模型")))
                .andExpect(jsonPath("$.outline[5].level").value(1))
                .andExpect(jsonPath("$.outline[6].title").value("参考文献"));
    }

    @Test
    void savedFullTextCanBeReadBackOnlyByItsOwner() throws Exception {
        String created = mockMvc.perform(post(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outlineRequest()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        // 全文由生成流程写入；这里直接写库，只验证「读回来」这条路
        var entity = thesisDocumentRepository.findById(id).orElseThrow();
        entity.saveFullText("""
                {"topic":"基于大语言模型的金融舆情分析系统","model":"stub-model",
                 "sections":[{"title":"第一章 绪论","level":1,"content":"正文内容"}],
                 "references":[],"referenceSource":"未启用","wordCount":4,
                 "targetWords":8000,"totalTokens":120}
                """, "stub-model", 4);
        thesisDocumentRepository.save(entity);

        mockMvc.perform(get(BASE + "/documents/" + id + "/full-text")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("stub-model"))
                .andExpect(jsonPath("$.wordCount").value(4))
                .andExpect(jsonPath("$.sections[0].title").value("第一章 绪论"));

        mockMvc.perform(get(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].hasFullText").value(true))
                .andExpect(jsonPath("$.items[0].fullTextWordCount").value(4));

        // 别人的稿件既不返回内容，也不暴露存在
        mockMvc.perform(get(BASE + "/documents/" + id + "/full-text")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftWithoutFullTextSaysSo() throws Exception {
        String created = mockMvc.perform(post(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outlineRequest()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get(BASE + "/documents/" + id + "/full-text")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankNodeTitleIsRejected() throws Exception {
        String created = mockMvc.perform(post(BASE + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outlineRequest()))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        String update = objectMapper.writeValueAsString(Map.of(
                "outline", List.of(Map.of("title", "   ", "level", 1))));

        mockMvc.perform(put(BASE + "/documents/" + id + "/outline")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isBadRequest());
    }
}
