package com.hub.backend.modules.redemption;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.redemption.dto.UserRedemptionOptionsResponse;
import com.hub.backend.modules.redemption.dto.UserRedemptionRequest;
import com.hub.backend.modules.redemption.dto.UserRedemptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/redemptions")
@Tag(name = "账户兑换", description = "当前登录用户使用 New API 兑换码充值")
public class UserRedemptionController {

    private final RedemptionService redemptionService;
    private final CurrentUserResolver currentUserResolver;

    public UserRedemptionController(RedemptionService redemptionService,
                                    CurrentUserResolver currentUserResolver) {
        this.redemptionService = redemptionService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "查询常用充值档位")
    @GetMapping("/options")
    public UserRedemptionOptionsResponse options(HttpServletRequest request) {
        currentUserResolver.requireAuthenticated(request);
        return redemptionService.userOptions();
    }

    @Operation(summary = "兑换额度",
            description = "兑换成功后额度直接进入当前登录用户绑定的 New API 账户。"
                    + "若服务端本会话已不持有该用户的登录密码（进程重启后），接口返回 428，"
                    + "客户端补交密码后重试同一请求即可。")
    @PostMapping("/redeem")
    public UserRedemptionResponse redeem(@Valid @RequestBody UserRedemptionRequest body,
                                         HttpServletRequest request) {
        return redemptionService.redeem(
                currentUserResolver.requireAuthenticated(request),
                body.code(),
                body.password());
    }
}
