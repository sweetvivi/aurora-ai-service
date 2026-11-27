package com.hub.backend.modules.admin.model;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.ModuleChatModelService;
import com.hub.backend.modules.appconfig.dto.ModuleChatModelsRequest;
import com.hub.backend.modules.appconfig.dto.ModuleChatModelsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "SITE_MODULE_CHAT_MODEL")
@RestController
@RequestMapping("/api/admin/v1/site-settings/module-chat-models")
public class ModuleChatModelAdminController {

    private final ChannelResolver channelResolver;
    private final ModuleChatModelService service;
    private final AdminGuard adminGuard;

    public ModuleChatModelAdminController(ChannelResolver channelResolver,
                                          ModuleChatModelService service,
                                          AdminGuard adminGuard) {
        this.channelResolver = channelResolver;
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    public ModuleChatModelsResponse get(HttpServletRequest request) {
        adminGuard.require(request);
        return service.load(channelResolver.resolve(request));
    }

    @PutMapping
    public ModuleChatModelsResponse update(@Valid @RequestBody ModuleChatModelsRequest body,
                                           HttpServletRequest request) {
        adminGuard.require(request);
        return service.update(channelResolver.resolve(request), body.models());
    }
}
