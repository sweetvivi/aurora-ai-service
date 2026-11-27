package com.hub.backend.modules.ppt;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.ppt.dto.PptDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/ppt/deck-templates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI PPT Decks", description = "官方 PPT 模板库（Deck）")
public class PptDeckTemplateController {

    private final PptDeckTemplateService service;
    private final CurrentUserResolver currentUserResolver;

    public PptDeckTemplateController(PptDeckTemplateService service, CurrentUserResolver currentUserResolver) {
        this.service = service;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "已上架官方 Deck 列表")
    @GetMapping
    public List<PptDtos.DeckTemplateResponse> list(HttpServletRequest request) {
        currentUserResolver.requireAuthenticated(request);
        return service.listPublished();
    }
}
