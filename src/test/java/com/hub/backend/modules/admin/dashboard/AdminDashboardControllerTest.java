package com.hub.backend.modules.admin.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=dashboard-test-key",
        "app.new-api.catalog-sync-on-startup=false"
})
@AutoConfigureMockMvc
@Transactional
class AdminDashboardControllerTest {

    private static final String ADMIN_KEY = "dashboard-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectsStatsAndCountsRecordedVisits() throws Exception {
        mockMvc.perform(get("/api/admin/v1/dashboard/stats"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/analytics/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/painting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(true));

        mockMvc.perform(post("/api/v1/analytics/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/admin/users\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorded").value(false));

        mockMvc.perform(get("/api/admin/v1/dashboard/stats")
                        .param("days", "7")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVisits", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalTokens", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.newUsers.days").value(7))
                .andExpect(jsonPath("$.newUsers.daily.length()").value(7))
                .andExpect(jsonPath("$.generatedAt").exists());
    }
}
