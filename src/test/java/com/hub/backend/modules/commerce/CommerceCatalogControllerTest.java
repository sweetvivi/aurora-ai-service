package com.hub.backend.modules.commerce;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CommerceCatalogControllerTest {

    private static final String TOOL_KEY = "public-catalog-test-tool";

    private static final String PAINTING_TOOL_KEY = "public-catalog-test-paint";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository sites;

    @Autowired
    private CommerceToolRepository tools;

    private Long siteId;

    @BeforeEach
    void setUp() {
        tools.findAll().stream()
                .filter(tool -> TOOL_KEY.equals(tool.getToolKey())
                        || PAINTING_TOOL_KEY.equals(tool.getToolKey()))
                .forEach(tools::delete);

        AppSiteEntity site = sites.findBySiteKey("default")
                .orElseGet(() -> sites.save(new AppSiteEntity("default", "Default Site")));
        siteId = site.getId();

        CommerceToolEntity tool = CommerceToolEntity.newTool();
        tool.setSiteId(siteId);
        tool.setToolKey(TOOL_KEY);
        tool.setCategory("测试分类");
        tool.setName("公开目录工具");
        tool.setForm("replace");
        tool.setSortOrder(1);
        tools.save(tool);

        // 同一张表里的另一个场景：默认目录必须看不见它
        CommerceToolEntity painting = CommerceToolEntity.newTool();
        painting.setSiteId(siteId);
        painting.setScene(CommerceToolEntity.SCENE_PAINTING);
        painting.setToolKey(PAINTING_TOOL_KEY);
        painting.setCategory("测试绘画分类");
        painting.setName("绘画工具");
        painting.setForm("retouch");
        painting.setSortOrder(2);
        tools.save(painting);
    }

    @Test
    void allowsAnonymousClientsToReadTheCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/commerce/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].label").value("测试分类"))
                .andExpect(jsonPath("$.categories[0].items[0].value").value(TOOL_KEY));
    }

    @Test
    void keepsPaintingToolsOutOfTheCommerceCatalog() throws Exception {
        // 两个场景共用 commerce_tools：漏掉 scene 条件，电商目录就会多出一批绘画工具，
        // 用户在工作台里会看到「老照片修复」这种跟电商无关的卡片。
        mockMvc.perform(get("/api/v1/commerce/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[*].items[*].value",
                        not(hasItem(PAINTING_TOOL_KEY))));
    }

    @Test
    void servesPaintingToolsOnlyWhenTheSceneIsAskedFor() throws Exception {
        mockMvc.perform(get("/api/v1/commerce/catalog").param("scene", "painting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].label").value("测试绘画分类"))
                .andExpect(jsonPath("$.categories[0].items[0].value").value(PAINTING_TOOL_KEY))
                .andExpect(jsonPath("$.categories[*].items[*].value",
                        not(hasItem(TOOL_KEY))));
    }

    @Test
    void rejectsAnUnknownSceneInsteadOfFallingBackToCommerce() throws Exception {
        // 写错场景名要当场发现：静默退回电商会让人以为「绘画工具没种进去」
        mockMvc.perform(get("/api/v1/commerce/catalog").param("scene", "draw"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allowsAnonymousClientsToReadOneTool() throws Exception {
        mockMvc.perform(get("/api/v1/commerce/tools/{toolKey}", TOOL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(TOOL_KEY))
                .andExpect(jsonPath("$.label").value("公开目录工具"));
    }

    @Test
    void stillRequiresAuthenticationToComposeWithATool() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "images", "product.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/commerce/tools/{toolKey}/compose/stream", TOOL_KEY)
                        .file(image)
                        .param("prompt", "生成一张商品图"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
