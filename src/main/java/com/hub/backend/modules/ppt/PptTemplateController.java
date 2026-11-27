package com.hub.backend.modules.ppt;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.ppt.dto.PptDtos.TemplateDetailResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.TemplateSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/ppt/templates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI PPT-模板", description = "模版库列表与详情")
public class PptTemplateController {

    private final PptTemplateService templateService;
    private final CurrentUserResolver currentUserResolver;

    public PptTemplateController(PptTemplateService templateService,
                                 CurrentUserResolver currentUserResolver) {
        this.templateService = templateService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "模版库列表")
    @GetMapping
    public List<TemplateSummaryResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String scene,
            HttpServletRequest request) {
        currentUserResolver.requireAuthenticated(request);
        return templateService.list(category, scene);
    }

    @Operation(summary = "模板详情")
    @GetMapping("/{templateId}")
    public TemplateDetailResponse detail(@PathVariable String templateId, HttpServletRequest request) {
        currentUserResolver.requireAuthenticated(request);
        return templateService.detail(templateId);
    }
}
