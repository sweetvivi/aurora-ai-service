package com.hub.backend.modules.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatCompletionResponse;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/v1/agents/{agentSn}/chat")
@Tag(name = "智能体对话", description = "复用模型对话协议，系统提示词与对话记录均按智能体隔离")
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final ChatProperties chatProperties;
    private final CurrentUserResolver currentUserResolver;
    private final AsyncTaskExecutor chatStreamExecutor;
    private final ObjectMapper objectMapper;

    public AgentChatController(AgentChatService agentChatService,
                               ChatProperties chatProperties,
                               CurrentUserResolver currentUserResolver,
                               @Qualifier("chatStreamExecutor") AsyncTaskExecutor chatStreamExecutor,
                               ObjectMapper objectMapper) {
        this.agentChatService = agentChatService;
        this.chatProperties = chatProperties;
        this.currentUserResolver = currentUserResolver;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "智能体对话（阻塞返回）")
    @PostMapping("/completions")
    public ChatCompletionResponse complete(@PathVariable String agentSn,
                                           @Valid @RequestBody ChatCompletionRequest request,
                                           HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentChatService.complete(agentSn, request, currentUser);
    }

    @Operation(summary = "智能体对话（SSE 流式返回）")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String agentSn,
                             @Valid @RequestBody ChatCompletionRequest request,
                             HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        AgentChatService.PreparedAgentChat prepared =
                agentChatService.prepare(agentSn, request, currentUser);
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
                    "Unable to serialise stream frame " + event.event(), exception);
        }
    }
}
