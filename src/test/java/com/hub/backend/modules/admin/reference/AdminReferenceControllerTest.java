package com.hub.backend.modules.admin.reference;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.model.entity.LlmModelGroupEntity;
import com.hub.backend.modules.model.repository.LlmModelGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two lists the console uses to fill selects.
 *
 * <p>They exist because the model form used to ask for a numeric {@code siteId} / {@code groupId},
 * with "leave it blank" meaning "global" — a form that cannot say what the numbers mean and that
 * only fails once the write path rejects the id.</p>
 */
@SpringBootTest(properties = "app.admin.api-key=admin-test-key")
@AutoConfigureMockMvc
class AdminReferenceControllerTest {

    private static final String ADMIN_KEY = "admin-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppSiteRepository siteRepository;

    @Autowired
    private LlmModelGroupRepository groupRepository;

    @BeforeEach
    void removeOwnFixtures() {
        // Only this class's own fixtures, so nothing another test class seeded is disturbed. The H2
        // instance is shared across test classes.
        groupRepository.findAll().stream()
                .filter(group -> group.getGroupKey().startsWith("reference-test-"))
                .forEach(groupRepository::delete);
    }

    @Test
    void listsSitesSoTheModelFormCanOfferASelect() throws Exception {
        siteRepository.findBySiteKey("reference-test-site")
                .orElseGet(() -> siteRepository.save(
                        new AppSiteEntity("reference-test-site", "参考站点")));

        String body = mockMvc.perform(get("/api/admin/v1/sites").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("reference-test-site"), body);
        assertTrue(body.contains("参考站点"), body);
    }

    @Test
    void listsModelGroupsSoTheModelFormCanOfferASelect() throws Exception {
        groupRepository.save(new LlmModelGroupEntity(
                null, "reference-test-group", "参考分组", "Ref"));

        mockMvc.perform(get("/api/admin/v1/model-groups").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[?(@.groupKey=='reference-test-group')].label")
                        .value(org.hamcrest.Matchers.contains("参考分组")));
    }

    @Test
    void requiresTheAdminKey() throws Exception {
        mockMvc.perform(get("/api/admin/v1/sites"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/v1/model-groups"))
                .andExpect(status().isUnauthorized());
    }
}
