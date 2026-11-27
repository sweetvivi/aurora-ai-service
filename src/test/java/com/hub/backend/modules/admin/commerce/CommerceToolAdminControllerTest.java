package com.hub.backend.modules.admin.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's CRUD over the e-commerce tool catalogue.
 *
 * <p>This is the write path that replaces hand-written SQL migrations for prompts, form schemas
 * and gallery examples, so the cases below cover the things that used to be impossible to fix
 * without a deploy: renaming, re-iconing, editing the schema, hiding a tool, and copying one.</p>
 */
@SpringBootTest(properties = "app.admin.api-key=admin-test-key")
@AutoConfigureMockMvc
class CommerceToolAdminControllerTest {

    private static final String ADMIN_KEY = "admin-test-key";
    private static final String BASE = "/api/admin/v1/commerce-tools";
    private static final String KEY_PREFIX = "admin-test-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CommerceToolRepository tools;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void removeOwnFixtures() {
        // The H2 instance is shared across test classes; only remove this class's rows.
        tools.findAll().stream()
                .filter(row -> row.getToolKey().startsWith(KEY_PREFIX))
                .forEach(tools::delete);
    }

    @Test
    void rejectsRequestsWithoutTheAdminKey() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsAToolAndFindsItByKeyword() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "toolKey", KEY_PREFIX + "new",
                                "category", "测试分类",
                                "name", "新工具",
                                "form", "replace",
                                "glyph", "ri-test-tube-line",
                                "sortOrder", 5))))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(get(BASE)
                        .param("keyword", KEY_PREFIX + "new")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("新工具"), body);
        assertEquals(1, readPage(body).get("total"));
    }

    @Test
    void rejectsDuplicateToolKey() throws Exception {
        createMinimal(KEY_PREFIX + "dup");

        mockMvc.perform(post(BASE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "toolKey", KEY_PREFIX + "dup",
                                "category", "测试分类",
                                "name", "重复",
                                "form", "replace"))))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsAFormTheWorkbenchCannotRender() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "toolKey", KEY_PREFIX + "bad-form",
                                "category", "测试分类",
                                "name", "坏表单",
                                "form", "not-a-form"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedFormSchemaBeforeItReachesTheCatalogue() throws Exception {
        mockMvc.perform(post(BASE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "toolKey", KEY_PREFIX + "bad-schema",
                                "category", "测试分类",
                                "name", "坏 schema",
                                "form", "replace",
                                "formSchema", Map.of(
                                        "uploads", "product",
                                        "fields", List.of(Map.of("key", "style", "label", "风格", "type", "radio")))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesIconNameAndFormSchema() throws Exception {
        long id = createMinimal(KEY_PREFIX + "edit");

        Map<String, Object> schema = Map.of(
                "uploads", List.of(Map.of("key", "product", "label", "上传商品图")),
                "gallery", List.of(Map.of("url", "https://example.com/a.jpg", "title", "案例 A")),
                "guide", Map.of("intro", "说明"));

        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "改名后的工具",
                                "glyph", "ri-store-2-line",
                                "accent", "#123456",
                                "formSchema", schema))))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get(BASE + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("改名后的工具"), body);
        assertTrue(body.contains("ri-store-2-line"), body);
        assertTrue(body.contains("案例 A"), body);
        // 没传的字段保持原值（PUT 是按字段更新）
        assertTrue(body.contains("测试分类"), body);
    }

    @Test
    void refusesToRenameTheToolKey() throws Exception {
        long id = createMinimal(KEY_PREFIX + "stable");

        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("toolKey", KEY_PREFIX + "renamed"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void iconImageTakesPrecedenceAndCanBeCleared() throws Exception {
        long id = createMinimal(KEY_PREFIX + "icon");

        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("iconUrl", "https://cdn.example.com/a.png"))))
                .andExpect(status().isOk());

        assertEquals("https://cdn.example.com/a.png", tools.findById(id).orElseThrow().getIconUrl());

        // 空字符串表示「回到文字图标」，不能当成「没传」被忽略
        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("iconUrl", ""))))
                .andExpect(status().isOk());

        assertNull(tools.findById(id).orElseThrow().getIconUrl());
    }

    @Test
    void purgeRefusesAnActiveToolAndRemovesAnArchivedOne() throws Exception {
        long id = createMinimal(KEY_PREFIX + "purge");

        // 未下架不允许彻底删除：这一步不可恢复，先下架给运营一次看见的机会
        mockMvc.perform(delete(BASE + "/" + id + "/purge").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest());
        assertTrue(tools.findById(id).isPresent());

        mockMvc.perform(delete(BASE + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(BASE + "/" + id + "/purge").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        assertTrue(tools.findById(id).isEmpty());
    }

    @Test
    void clearingTheSchemaWithAnEmptyObjectWritesNull() throws Exception {        long id = createMinimal(KEY_PREFIX + "clear");

        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("formSchema", Map.of("guide", Map.of("intro", "x"))))))
                .andExpect(status().isOk());

        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("formSchema", Map.of()))))
                .andExpect(status().isOk());

        CommerceToolEntity row = tools.findById(id).orElseThrow();
        assertNull(row.getFormSchema());
    }

    @Test
    void softDeleteHidesTheToolFromActiveListing() throws Exception {
        long id = createMinimal(KEY_PREFIX + "hide");

        mockMvc.perform(delete(BASE + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        assertEquals(CommerceToolEntity.STATUS_DELETED, tools.findById(id).orElseThrow().getStatus());

        String active = mockMvc.perform(get(BASE)
                        .param("keyword", KEY_PREFIX + "hide")
                        .param("status", "ACTIVE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, readPage(active).get("total"));

        String deleted = mockMvc.perform(get(BASE)
                        .param("keyword", KEY_PREFIX + "hide")
                        .param("status", "DELETED")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, readPage(deleted).get("total"));
    }

    @Test
    void duplicateCopiesPromptAndSchemaUnderANewKey() throws Exception {
        long id = createMinimal(KEY_PREFIX + "copy-src");

        mockMvc.perform(put(BASE + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("prompt", "原始提示词"))))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post(BASE + "/" + id + "/duplicate")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(KEY_PREFIX + "copy-src-copy"), body);
        assertTrue(body.contains("原始提示词"), body);
        assertTrue(body.contains("（副本）"), body);
    }

    @Test
    void categoriesKeepTheCatalogueOrderAndIncludeDeletedOnes() throws Exception {
        long first = createMinimal(KEY_PREFIX + "cat-a", "ZZ 测试分类", 900);
        createMinimal(KEY_PREFIX + "cat-b", "ZZ 测试分类", 901);
        mockMvc.perform(delete(BASE + "/" + first).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        String body = mockMvc.perform(get(BASE + "/categories").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 去重后只有一个，且下架后依然出现在下拉里，否则运营无法按分类筛出它再恢复
        assertEquals(1, body.split("ZZ 测试分类", -1).length - 1, body);
        assertTrue(body.contains("ZZ 测试分类"), body);
    }

    @Test
    void categoriesCanBeScopedToAScene() throws Exception {
        // 管理端把电商与绘画拆成两个页面后，分类下拉必须跟着场景走：
        // 绘画页看到 15 个电商分类，运营根本没法选。
        createMinimal(KEY_PREFIX + "cat-commerce", "ZZ 电商分类", 910);
        mockMvc.perform(post(BASE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "toolKey", KEY_PREFIX + "cat-painting",
                                "category", "ZZ 绘画分类",
                                "name", "测试工具",
                                "form", "retouch",
                                "scene", "painting",
                                "sortOrder", 911))))
                .andExpect(status().isCreated());

        String commerce = mockMvc.perform(get(BASE + "/categories?scene=commerce")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(commerce.contains("ZZ 电商分类"), commerce);
        assertFalse(commerce.contains("ZZ 绘画分类"), commerce);

        String painting = mockMvc.perform(get(BASE + "/categories?scene=painting")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(painting.contains("ZZ 绘画分类"), painting);
        assertFalse(painting.contains("ZZ 电商分类"), painting);

        // 不传 scene 仍然返回全部，旧调用方不受影响
        String all = mockMvc.perform(get(BASE + "/categories").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(all.contains("ZZ 电商分类"), all);
        assertTrue(all.contains("ZZ 绘画分类"), all);
    }

    /* ------------------------------ helpers ------------------------------ */

    private long createMinimal(String toolKey) throws Exception {
        return createMinimal(toolKey, "测试分类", 0);
    }

    private long createMinimal(String toolKey, String category, int sortOrder) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "toolKey", toolKey,
                                "category", category,
                                "name", "测试工具",
                                "form", "replace",
                                "sortOrder", sortOrder))))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> body = readPage(result.getResponse().getContentAsString());
        Object id = body.get("id");
        assertNotNull(id, "create response should carry the new id");
        return ((Number) id).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPage(String body) throws Exception {
        return objectMapper.readValue(body, LinkedHashMap.class);
    }

    private String json(Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }
}
