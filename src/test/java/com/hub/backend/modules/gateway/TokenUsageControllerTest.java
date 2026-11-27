package com.hub.backend.modules.gateway;

import com.fasterxml.jackson.databind.node.NullNode;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.gateway.dto.TokenConsumptionLogResponse;
import com.hub.backend.modules.gateway.dto.TokenUsageHistoryResponse;
import com.hub.backend.modules.gateway.dto.TokenUsageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TokenUsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserGatewayTokenService tokenService;

    @MockitoBean
    private TokenUsageHistoryService historyService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @Test
    void returnsAZeroBalanceAsASuccessfulUsageSnapshot() throws Exception {
        when(currentUserResolver.requireAuthenticated(any()))
                .thenReturn(new CurrentUser(7L, 1L, "demo"));
        when(tokenService.usage(any(), anyBoolean())).thenReturn(new TokenUsageResponse(
                "sk-use...-key",
                "hub-1-u7",
                100,
                100,
                0,
                "CNY",
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("500000"),
                new BigDecimal("7.3"),
                false,
                NullNode.getInstance(),
                false,
                null,
                Instant.parse("2026-10-20T00:00:00Z")));

        mockMvc.perform(get("/api/v1/usage/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedKey").value("sk-use...-key"))
                .andExpect(jsonPath("$.tokenName").value("hub-1-u7"))
                .andExpect(jsonPath("$.totalGranted").value(100))
                .andExpect(jsonPath("$.totalUsed").value(100))
                .andExpect(jsonPath("$.totalAvailable").value(0))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.totalGrantedCny").value(0.00))
                .andExpect(jsonPath("$.totalUsedCny").value(0.00))
                .andExpect(jsonPath("$.totalAvailableCny").value(0.00))
                .andExpect(jsonPath("$.quotaPerUnit").value(500000))
                .andExpect(jsonPath("$.usdExchangeRate").value(7.3))
                .andExpect(jsonPath("$.lastSyncedAt").value("2026-10-20T00:00:00Z"));
    }

    @Test
    void forwardsTheExplicitRefreshFlagToTheUsageService() throws Exception {
        when(currentUserResolver.requireAuthenticated(any()))
                .thenReturn(new CurrentUser(7L, 1L, "demo"));

        mockMvc.perform(get("/api/v1/usage/token").param("refresh", "true"))
                .andExpect(status().isOk());

        verify(tokenService).usage(any(), eq(true));
    }

    @Test
    void returnsDailyTokenUsageForTheRequestedPeriod() throws Exception {
        when(currentUserResolver.requireAuthenticated(any()))
                .thenReturn(new CurrentUser(7L, 1L, "demo"));
        when(historyService.history(any(), org.mockito.ArgumentMatchers.eq(30)))
                .thenReturn(new TokenUsageHistoryResponse(
                        30,
                        LocalDate.parse("2026-09-23"),
                        LocalDate.parse("2026-10-22"),
                        600,
                        400,
                        1000,
                        List.of(new TokenUsageHistoryResponse.DailyUsage(
                                LocalDate.parse("2026-10-22"), 20, 10, 30))));

        mockMvc.perform(get("/api/v1/usage/token/history").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.totalPromptTokens").value(600))
                .andExpect(jsonPath("$.totalCompletionTokens").value(400))
                .andExpect(jsonPath("$.totalTokens").value(1000))
                .andExpect(jsonPath("$.daily[0].date").value("2026-10-22"))
                .andExpect(jsonPath("$.daily[0].totalTokens").value(30));
    }

    @Test
    void returnsPaginatedTokenConsumptionLogs() throws Exception {
        when(currentUserResolver.requireAuthenticated(any()))
                .thenReturn(new CurrentUser(7L, 1L, "demo"));
        when(tokenService.consumptionLogs(any(), eq(2), eq(20)))
                .thenReturn(new TokenConsumptionLogResponse(
                        2,
                        20,
                        41,
                        List.of(new TokenConsumptionLogResponse.Item(
                                901,
                                Instant.parse("2026-10-20T12:30:00Z"),
                                "gpt-5",
                                "hub-1-u7",
                                120,
                                80,
                                200,
                                20_548,
                                new BigDecimal("0.3000"),
                                3,
                                true,
                                "consume"))));

        mockMvc.perform(get("/api/v1/usage/token/logs")
                        .param("page", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.items[0].id").value(901))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-20T12:30:00Z"))
                .andExpect(jsonPath("$.items[0].modelName").value("gpt-5"))
                .andExpect(jsonPath("$.items[0].totalTokens").value(200))
                .andExpect(jsonPath("$.items[0].amountCny").value(0.3000))
                .andExpect(jsonPath("$.items[0].stream").value(true))
                .andExpect(jsonPath("$.items[0].type").value("consume"));
    }

    @Test
    void rejectsInvalidTokenLogPaginationBeforeCallingNewApi() throws Exception {
        mockMvc.perform(get("/api/v1/usage/token/logs").param("page", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/usage/token/logs")
                        .param("page", "1")
                        .param("pageSize", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/usage/token/logs")
                        .param("page", "1")
                        .param("pageSize", "101"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(tokenService);
    }
}
