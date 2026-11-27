package com.hub.backend.modules.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.modules.agent.AgentChatService;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.support.dto.SupportHandoffRequest;
import com.hub.backend.modules.support.dto.SupportHandoffResponse;
import com.hub.backend.modules.support.dto.SupportWidgetConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "站内客服", description = "悬浮挂件配置、AI 对话与转人工")
public class SupportController {

    private final SupportBotService botService;
    private final SupportChatService chatService;
    private final SupportHandoffService handoffService;
    private final AgentChatService agentChatService;
    private final ChatProperties chatProperties;
    private final CurrentUserResolver currentUserResolver;
    private final AsyncTaskExecutor chatStreamExecutor;
    private final ObjectMapper objectMapper;

    public SupportController(SupportBotService botService,
                             SupportChatService chatService,
                             SupportHandoffService handoffService,
                             AgentChatService agentChatService,
                             ChatProperties chatProperties,
                             CurrentUserResolver currentUserResolver,
                             @Qualifier("chatStreamExecutor") AsyncTaskExecutor chatStreamExecutor,
                             ObjectMapper objectMapper) {
        this.botService = botService;
        this.chatService = chatService;
        this.handoffService = handoffService;
        this.agentChatService = agentChatService;
        this.chatProperties = chatProperties;
        this.currentUserResolver = currentUserResolver;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "挂件公开配置（可匿名）")
    @GetMapping("/widget-config")
    public SupportWidgetConfigResponse widgetConfig(HttpServletRequest httpRequest) {
        CurrentUser user = currentUserResolver.resolveOptional(httpRequest);
        Long siteId = user == null ? null : user.siteId();
        if (siteId == null) {
            // Fall back to default-site config seeded by Flyway.
            siteId = botService.requireConfig(null).getSiteId();
        }
        return botService.widgetConfig(siteId);
    }

    @Operation(summary = "客服对话（SSE，需登录）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatCompletionRequest request,
                             HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        AgentChatService.PreparedAgentChat prepared = chatService.prepare(request, currentUser);
        SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeout().toMillis());
        chatStreamExecutor.execute(() -> {
            try {
                agentChatService.stream(prepared, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    @Operation(summary = "转人工工单（登录可选；关联会话需登录且为本人）")
    @PostMapping("/handoff")
    public SupportHandoffResponse handoff(@Valid @RequestBody SupportHandoffRequest request,
                                          HttpServletRequest httpRequest) {
        CurrentUser user = currentUserResolver.resolveOptional(httpRequest);
        if (request.agentConversationId() != null) {
            user = currentUserResolver.requireAuthenticated(httpRequest);
        }
        return handoffService.handoff(user, request);
    }

    private void send(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.event()).data(serialise(event)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String serialise(ChatStreamEvent event) {
        try {
            return objectMapper.writeValueAsString(event.data());
        } catch (JsonProcessingException exception) {
            throw new LlmInvocationException(
                    "客服流式帧序列化失败：" + event.event(), exception);
        }
    }
}
