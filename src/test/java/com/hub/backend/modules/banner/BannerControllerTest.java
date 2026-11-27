package com.hub.backend.modules.banner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.banner.repository.SiteBannerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.admin.api-key=admin-test-key",
        "app.admin.require-api-key=true"
})
@AutoConfigureMockMvc
class BannerControllerTest {

    private static final String ADMIN_URL = "/api/admin/v1/banners";
    private static final String CURRENT_URL = "/api/v1/banners/current";
    private static final String ADMIN_KEY = "admin-test-key";
    private static final String SITE_KEY = "banner-test-site";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SiteBannerRepository bannerRepository;

    @Autowired
    private AppSiteRepository siteRepository;

    private AppSiteEntity site;

    @BeforeEach
    void setUpSite() {
        bannerRepository.deleteAll();
        site = siteRepository.findBySiteKey(SITE_KEY)
                .orElseGet(() -> siteRepository.save(new AppSiteEntity(SITE_KEY, "Banner Test Site")));
    }

    @AfterEach
    void removeFixtures() {
        bannerRepository.deleteAll();
    }

    @Test
    void createsReadsUpdatesChangesStatusAndDeletesBanner() throws Exception {
        String createdBody = mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"  四周年会员活动  ",
                                  "title":"  Aurora AI 四周年庆  ",
                                  "content":"  会员低至 8 折，畅享全场模型与工具  ",
                                  "highlightText":"  8 折  ",
                                  "buttonText":"  查看活动  ",
                                  "linkUrl":"  /user-center/points  ",
                                  "imageUrl":"  /uploads/banner/anniversary.webp  ",
                                  "status":"ACTIVE",
                                  "siteId":%d,
                                  "startAt":"2026-10-20T00:00:00",
                                  "endAt":"2026-10-31T23:59:59",
                                  "dismissible":true,
                                  "sortOrder":10
                                }
                                """.formatted(site.getId())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.name").value("四周年会员活动"))
                .andExpect(jsonPath("$.title").value("Aurora AI 四周年庆"))
                .andExpect(jsonPath("$.content").value("会员低至 8 折，畅享全场模型与工具"))
                .andExpect(jsonPath("$.highlightText").value("8 折"))
                .andExpect(jsonPath("$.buttonText").value("查看活动"))
                .andExpect(jsonPath("$.linkUrl").value("/user-center/points"))
                .andExpect(jsonPath("$.imageUrl").value("/uploads/banner/anniversary.webp"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.siteId").value(site.getId()))
                .andExpect(jsonPath("$.sortOrder").value(10))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(createdBody).path("id").asLong();

        mockMvc.perform(get(ADMIN_URL + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("四周年会员活动"));

        mockMvc.perform(put(ADMIN_URL + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"普通活动",
                                  "title":"新的标题",
                                  "content":"新的正文",
                                  "highlightText":null,
                                  "buttonText":null,
                                  "linkUrl":null,
                                  "imageUrl":null,
                                  "status":"DISABLED",
                                  "siteId":null,
                                  "startAt":null,
                                  "endAt":null,
                                  "dismissible":false,
                                  "sortOrder":0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("普通活动"))
                .andExpect(jsonPath("$.highlightText").value((String) null))
                .andExpect(jsonPath("$.buttonText").value((String) null))
                .andExpect(jsonPath("$.linkUrl").value((String) null))
                .andExpect(jsonPath("$.imageUrl").value((String) null))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.siteId").value((Long) null))
                .andExpect(jsonPath("$.dismissible").value(false));

        mockMvc.perform(put(ADMIN_URL + "/" + id + "/status")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.title").value("新的标题"))
                .andExpect(jsonPath("$.dismissible").value(false));

        mockMvc.perform(delete(ADMIN_URL + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ADMIN_URL + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersAndSortsTheAdminList() throws Exception {
        long siteFirst = createBanner("Site First", site.getId(), "ACTIVE", 1);
        long global = createBanner("Global", null, "ACTIVE", 5);
        long siteDisabled = createBanner("Site Disabled", site.getId(), "DISABLED", 0);

        mockMvc.perform(get(ADMIN_URL).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(siteDisabled))
                .andExpect(jsonPath("$[1].id").value(siteFirst))
                .andExpect(jsonPath("$[2].id").value(global));

        mockMvc.perform(get(ADMIN_URL)
                        .param("status", "ACTIVE")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get(ADMIN_URL)
                        .param("siteId", "0")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(global));

        mockMvc.perform(get(ADMIN_URL)
                        .param("siteId", String.valueOf(site.getId()))
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get(ADMIN_URL)
                        .param("keyword", "disabled")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(siteDisabled));
    }

    @Test
    void rejectsUnsafeUrlsBrokenButtonPairsUnknownSitesAndInvalidWindows() throws Exception {
        mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Unsafe",
                                  "title":"Unsafe",
                                  "content":"Unsafe",
                                  "buttonText":"Open",
                                  "linkUrl":"javascript:alert(1)"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("linkUrl")));

        mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Unsafe image",
                                  "title":"Unsafe image",
                                  "content":"Unsafe image",
                                  "imageUrl":"data:image/png;base64,AAAA"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("imageUrl")));

        mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Broken pair",
                                  "title":"Broken pair",
                                  "content":"Only a button label",
                                  "buttonText":"Open"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("buttonText")));

        mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Unknown site",
                                  "title":"Unknown site",
                                  "content":"No such site",
                                  "siteId":999999
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("siteId")));

        mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Bad window",
                                  "title":"Bad window",
                                  "content":"End before start",
                                  "startAt":"2026-10-20T00:00:00",
                                  "endAt":"2026-10-19T00:00:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("endAt")));
    }

    @Test
    void publicEndpointPrefersSiteBannerAndHonorsStatusAndTimeWindow() throws Exception {
        long global = createBanner("Global fallback", null, "ACTIVE", 0);
        long siteBanner = createBanner("Site campaign", site.getId(), "ACTIVE", 100);

        mockMvc.perform(get(CURRENT_URL).header("app-name", SITE_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "public, max-age=30, stale-while-revalidate=60"))
                .andExpect(jsonPath("$.id").value(siteBanner));

        mockMvc.perform(put(ADMIN_URL + "/" + siteBanner + "/status")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(CURRENT_URL).header("app-name", SITE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(global));

        updateWindow(global, "2099-01-01T00:00:00", null);
        mockMvc.perform(get(CURRENT_URL).header("app-name", SITE_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));

        updateWindow(global, null, "2000-01-01T00:00:00");
        mockMvc.perform(get(CURRENT_URL).header("app-name", SITE_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }

    @Test
    void requiresTheAdminKey() throws Exception {
        mockMvc.perform(get(ADMIN_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishesTheBannerPathsInTheOpenApiDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode paths = objectMapper.readTree(body).path("paths");
        org.junit.jupiter.api.Assertions.assertTrue(paths.has(ADMIN_URL));
        org.junit.jupiter.api.Assertions.assertTrue(paths.has(ADMIN_URL + "/{id}"));
        org.junit.jupiter.api.Assertions.assertTrue(paths.has(ADMIN_URL + "/{id}/status"));
        org.junit.jupiter.api.Assertions.assertTrue(paths.has(CURRENT_URL));
        org.junit.jupiter.api.Assertions.assertTrue(
                objectMapper.readTree(body).path("components").path("schemas").has("Banner"));
    }

    private long createBanner(String name, Long siteId, String status, int sortOrder) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("title", name);
        body.put("content", name + " content");
        body.put("status", status);
        body.put("siteId", siteId);
        body.put("dismissible", true);
        body.put("sortOrder", sortOrder);

        String response = mockMvc.perform(post(ADMIN_URL)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).path("id").asLong();
    }

    private void updateWindow(long id, String startAt, String endAt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "Window test");
        body.put("title", "Window test");
        body.put("content", "Window test content");
        body.put("status", "ACTIVE");
        body.put("dismissible", true);
        body.put("sortOrder", 0);
        body.put("startAt", startAt);
        body.put("endAt", endAt);

        mockMvc.perform(put(ADMIN_URL + "/" + id)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isOk());
    }
}
