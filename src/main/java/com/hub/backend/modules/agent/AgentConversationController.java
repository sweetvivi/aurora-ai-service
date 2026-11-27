package com.hub.backend.modules.agent;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.modules.agent.dto.AgentChatMessageCursorResponse;
import com.hub.backend.modules.agent.dto.AgentChatMessageResponse;
import com.hub.backend.modules.agent.dto.AgentConversationSummaryResponse;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents/conversations")
@Tag(name = "智能体会话", description = "智能体独立对话记录")
public class AgentConversationController {

    private final AgentService agentService;
    private final CurrentUserResolver currentUserResolver;

    public AgentConversationController(AgentService agentService,
                                       CurrentUserResolver currentUserResolver) {
        this.agentService = agentService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "智能体会话列表")
    @GetMapping
    public PageResponse<AgentConversationSummaryResponse> list(
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.listConversations(currentUser.userId(), agentId, categoryId, keyword, page, size);
    }

    @Operation(summary = "智能体会话详情")
    @GetMapping("/{id}")
    public AgentConversationSummaryResponse detail(@PathVariable Long id,
                                                   HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.getConversation(id, currentUser.userId());
    }

    @Operation(summary = "智能体会话消息")
    @GetMapping("/{id}/messages")
    public AgentChatMessageCursorResponse messages(
            @PathVariable Long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.listMessages(id, currentUser.userId(), cursor, size);
    }

    @Operation(summary = "切换消息版本", description = "把同组的某个版本设为活跃，其余版本停用；切用户消息版本时同步换掉配对的答案")
    @PostMapping("/{id}/messages/{messageId}/activate")
    public AgentChatMessageResponse activate(@PathVariable Long id,
                                             @PathVariable Long messageId,
                                             HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.activateMessage(id, currentUser.userId(), messageId);
    }

    @Operation(summary = "删除智能体会话")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        agentService.deleteConversation(id, currentUser.userId());
        return ResponseEntity.noContent().build();
    }
}
