package com.hub.backend.modules.admin.logging;

import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import com.hub.backend.testsupport.AuthenticatedControllerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端运行日志要把 {@code user_id} 翻译成用户名。
 *
 * <p>日志表只存 id，一列数字对不上是谁；但账号注销后行就没了，所以「查不到名字」
 * 必须是合法结果而不是 500。这两条是本测试的重点，顺带守住按用户名搜索。</p>
 */
@SpringBootTest(properties = {
        "app.admin.require-api-key=true",
        "app.admin.api-key=admin-test-key"
})
@AutoConfigureMockMvc
class AdminLogControllerTest extends AuthenticatedControllerTestSupport {

    private static final String ADMIN_KEY = "admin-test-key";
    private static final String REQUESTS = "/api/admin/v1/logs/requests";
    private static final String AUDITS = "/api/admin/v1/logs/audits";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AppSiteRepository appSiteRepository;
    @Autowired
    private UserAccountRepository userAccountRepository;

    private Long siteId;
    private Long userId;
    /** 日志里出现过、但账号行已被注销流程物理删除的 id。 */
    private Long ghostId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM request_logs");
        jdbc.update("DELETE FROM audit_logs");
        userAccountRepository.deleteAll();
        appSiteRepository.deleteAll();

        AppSiteEntity site = appSiteRepository.save(new AppSiteEntity("log-site", "Log Site"));
        siteId = site.getId();
        UserAccountEntity user = userAccountRepository.saveAndFlush(
                new UserAccountEntity(siteId, "log-viewer", "Log Viewer"));
        userId = user.getId();
        ghostId = userId + 1000;

        insertRequest(userId, "probe-visible");
        insertRequest(ghostId, "probe-deleted");
        insertRequest(null, "probe-anonymous");

        insertAudit("REVOKE_GATEWAY_TOKEN", "USER", String.valueOf(userId));
        insertAudit("DELETE_USER", "ADMIN_KEY", "admin-key");
    }

    @Test
    void requestListResolvesUserIdToUsername() throws Exception {
        mockMvc.perform(get(REQUESTS)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("userId", String.valueOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].userId").value(userId))
                .andExpect(jsonPath("$.list[0].username").value("log-viewer"));
    }

    @Test
    void requestListKeepsIdWhenAccountWasDeleted() throws Exception {
        mockMvc.perform(get(REQUESTS)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("userId", String.valueOf(ghostId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].userId").value(ghostId))
                .andExpect(jsonPath("$.list[0].username").value(nullValue()));
    }

    @Test
    void requestDetailAlsoCarriesUsername() throws Exception {
        long id = jdbc.queryForObject(
                "SELECT id FROM request_logs WHERE user_id = ?", Long.class, userId);

        mockMvc.perform(get(REQUESTS + "/" + id).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("log-viewer"));
    }

    /** 排查时人记得的是用户名，不是 id，所以 keyword 必须能按用户名命中。 */
    @Test
    void requestKeywordMatchesUsername() throws Exception {
        mockMvc.perform(get(REQUESTS)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("keyword", "log-viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].username").value("log-viewer"));
    }

    @Test
    void auditListResolvesUserActorButLeavesAdminKeyAlone() throws Exception {
        mockMvc.perform(get(AUDITS)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("action", "REVOKE_GATEWAY_TOKEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].actorType").value("USER"))
                .andExpect(jsonPath("$.list[0].actorId").value(String.valueOf(userId)))
                .andExpect(jsonPath("$.list[0].actorUsername").value("log-viewer"));

        // 管理密钥的 actor_id 是 "admin-key"，不是用户 id，不该去 user_accounts 里查。
        mockMvc.perform(get(AUDITS)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("action", "DELETE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].actorType").value("ADMIN_KEY"))
                .andExpect(jsonPath("$.list[0].actorUsername").value(nullValue()));
    }

    @Test
    void auditKeywordMatchesUsername() throws Exception {
        mockMvc.perform(get(AUDITS)
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("keyword", "log-viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].actorUsername").value("log-viewer"));
    }

    private void insertRequest(Long uid, String route) {
        jdbc.update("""
                INSERT INTO request_logs
                    (trace_id, request_time, level, user_id, site_id, http_method, route, uri,
                     response_status, outcome, duration_ms, created_at)
                VALUES (?, CURRENT_TIMESTAMP, 'INFO', ?, ?, 'GET', ?, ?, 200, 'SUCCESS', 12, CURRENT_TIMESTAMP)
                """, "trace-" + route, uid, siteId, "/api/v1/" + route, "/api/v1/" + route);
    }

    private void insertAudit(String action, String actorType, String actorId) {
        jdbc.update("""
                INSERT INTO audit_logs
                    (occurred_at, action, target, target_id, actor_type, actor_id, outcome, duration_ms,
                     created_at)
                VALUES (CURRENT_TIMESTAMP, ?, 'USER_BALANCE', ?, ?, ?, 'SUCCESS', 5, CURRENT_TIMESTAMP)
                """, action, String.valueOf(userId), actorType, actorId);
    }
}
