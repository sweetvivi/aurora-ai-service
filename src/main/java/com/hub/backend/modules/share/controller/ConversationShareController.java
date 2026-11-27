package com.hub.backend.modules.share.controller;

import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.share.dto.ConversationShareCreateRequest;
import com.hub.backend.modules.share.dto.ConversationShareResponse;
import com.hub.backend.modules.share.dto.SharedConversationResponse;
import com.hub.backend.modules.share.service.ConversationShareService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ConversationShareController {

    private final ConversationShareService shareService;
    private final CurrentUserResolver currentUserResolver;

    public ConversationShareController(ConversationShareService shareService,
                                       CurrentUserResolver currentUserResolver) {
        this.shareService = shareService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "创建会话分享链接")
    @PostMapping("/conversations/{id}/shares")
    public ConversationShareResponse create(@PathVariable Long id,
                                            @RequestBody(required = false) @Valid ConversationShareCreateRequest request,
                                            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return shareService.create(id, currentUser.userId(), request);
    }

    @Operation(summary = "读取公开分享快照")
    @GetMapping("/shares/{token}")
    public SharedConversationResponse read(@PathVariable String token) {
        return shareService.read(token);
    }

    @Operation(summary = "列出我的分享链接")
    @GetMapping("/conversations/{id}/shares")
    public List<ConversationShareResponse> list(@PathVariable Long id,
                                                HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return shareService.list(id, currentUser.userId());
    }

    @Operation(summary = "撤销分享链接")
    @DeleteMapping("/conversations/{id}/shares/{shareId}")
    public ResponseEntity<Void> revoke(@PathVariable Long id,
                                       @PathVariable Long shareId,
                                       HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        shareService.revoke(id, shareId, currentUser.userId());
        return ResponseEntity.noContent().build();
    }
}
