package com.hub.backend.modules.admin.education;

import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.EducationAppearanceService;
import com.hub.backend.modules.appconfig.dto.EducationAppearanceRequest;
import com.hub.backend.modules.appconfig.dto.EducationAppearanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "EDUCATION_APPEARANCE")
@RestController
@RequestMapping("/api/admin/v1/education-appearance")
@Tag(name = "AI 教育后台", description = "AI 教育首页展示配置维护")
public class EducationAppearanceAdminController {

    private final ChannelResolver channelResolver;
    private final EducationAppearanceService service;
    private final AdminGuard adminGuard;

    public EducationAppearanceAdminController(ChannelResolver channelResolver,
                                              EducationAppearanceService service,
                                              AdminGuard adminGuard) {
        this.channelResolver = channelResolver;
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    @Operation(summary = "读取 AI 教育首页展示配置")
    public EducationAppearanceResponse get(HttpServletRequest request) {
        adminGuard.require(request);
        return service.load(channelResolver.resolve(request));
    }

    @PutMapping
    @Operation(summary = "保存 AI 教育首页展示配置")
    public EducationAppearanceResponse update(@Valid @RequestBody EducationAppearanceRequest body,
                                              HttpServletRequest request) {
        adminGuard.require(request);
        return service.update(channelResolver.resolve(request), body);
    }
}
