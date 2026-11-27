package com.hub.backend.modules.gateway;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.gateway.dto.TokenConsumptionLogResponse;
import com.hub.backend.modules.gateway.dto.TokenUsageHistoryResponse;
import com.hub.backend.modules.gateway.dto.TokenUsageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
@Tag(name = "账户用量", description = "直接读取 New API 中当前账户 Relay Token 的额度快照")
public class TokenUsageController {

    private final UserGatewayTokenService tokenService;
    private final TokenUsageHistoryService historyService;
    private final CurrentUserResolver currentUserResolver;

    public TokenUsageController(UserGatewayTokenService tokenService,
                                TokenUsageHistoryService historyService,
                                CurrentUserResolver currentUserResolver) {
        this.tokenService = tokenService;
        this.historyService = historyService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "查询当前账户 Token 用量",
            description = "refresh=true 时可在扣费 / 退款后绕过快照缓存对一次账"
                    + "（仍受最小刷新间隔约束，保护 New API 的调用限额）")
    @GetMapping("/token")
    public TokenUsageResponse token(
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletRequest request) {
        return tokenService.usage(currentUserResolver.requireAuthenticated(request), refresh);
    }

    @Operation(summary = "查询当前账户按日 Token 用量")
    @GetMapping("/token/history")
    public TokenUsageHistoryResponse tokenHistory(
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest request) {
        return historyService.history(
                currentUserResolver.requireAuthenticated(request), days);
    }

    @Operation(summary = "查询当前账户单次 Token 消耗日志")
    @GetMapping("/token/logs")
    public TokenConsumptionLogResponse tokenLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        if (page < 1) {
            throw new InvalidRequestException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new InvalidRequestException("pageSize must be between 1 and 100");
        }
        return tokenService.consumptionLogs(
                currentUserResolver.requireAuthenticated(request), page, pageSize);
    }
}
