package com.hub.backend.modules.appconfig;

import com.hub.backend.modules.appconfig.dto.EducationAppearanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/education")
@Tag(name = "AI 教育配置", description = "AI 教育首页公开展示配置")
public class EducationAppearanceController {

    private final ChannelResolver channelResolver;
    private final EducationAppearanceService service;

    public EducationAppearanceController(ChannelResolver channelResolver,
                                         EducationAppearanceService service) {
        this.channelResolver = channelResolver;
        this.service = service;
    }

    @GetMapping("/appearance")
    @Operation(summary = "获取 AI 教育首页展示配置")
    public EducationAppearanceResponse appearance(HttpServletRequest request) {
        return service.load(channelResolver.resolve(request));
    }
}
