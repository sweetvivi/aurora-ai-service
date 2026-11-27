package com.hub.backend.modules.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.chat.dto.ChatCompletionRequest;
import com.hub.backend.modules.chat.dto.ChatCompletionResponse;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * AI conversation endpoints.
 *
 * <p>{@code /completions} answers with a single JSON body, {@code /stream} pushes the same turn as
 * server sent events so a client can render tokens as they arrive.</p>
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "AI 对话", description = "基于 OpenAI 兼容网关的对话能力，包含阻塞式与流式两个入口")
public class ChatController {

    private final ChatService chatService;
    private final ChatProperties chatProperties;
    private final CurrentUserResolver currentUserResolver;
    private final AsyncTaskExecutor chatStreamExecutor;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService,
                          ChatProperties chatProperties,
                          CurrentUserResolver currentUserResolver,
                          @Qualifier("chatStreamExecutor") AsyncTaskExecutor chatStreamExecutor,
                          ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.chatProperties = chatProperties;
        this.currentUserResolver = currentUserResolver;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "对话（阻塞返回）",
            description = """
                    提交一轮对话，等待模型生成完毕后一次性返回完整结果。

                    同一轮只持久化最后一条 `user` 消息与本次 `assistant` 回复，历史上下文由调用方在
                    `messages` 中自行携带。传入 `conversationId` 可追加到已有会话，缺省则自动新建。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "对话成功"),
            @ApiResponse(responseCode = "400", description = "参数校验失败，或消息中没有 user 轮次",
                    content = @Content(schema = @Schema(implementation = com.hub.backend.common.ApiError.class))),
            @ApiResponse(responseCode = "404", description = "会话不存在，或所选模型已下架",
                    content = @Content(schema = @Schema(implementation = com.hub.backend.common.ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未提供有效的登录凭证"),
            @ApiResponse(responseCode = "402", description = "New API 账户余额不足"),
            @ApiResponse(responseCode = "503", description = "New API 计费或余额查询暂时不可用"),
            @ApiResponse(responseCode = "502", description = "上游模型网关不可达或返回错误",
                    content = @Content(schema = @Schema(implementation = com.hub.backend.common.ApiError.class)))
    })
    @PostMapping("/completions")
    public ChatCompletionResponse complete(@Valid @RequestBody ChatCompletionRequest request,
                                           HttpServletRequest httpRequest) {
        return chatService.complete(request, currentUserResolver.requireAuthenticated(httpRequest));
    }

    @Operation(
            summary = "对话（SSE 流式返回）",
            description = """
                    与阻塞接口同一轮对话，改为 Server-Sent Events 逐步下发。

                    事件序列：`message.created` → （`message.thinking`）→ 多个 `message.delta` →
                    `message.completed`；上游异常时以 `message.failed` 结束，
                    被 `POST /api/v1/chat/requests/{requestId}/cancel` 停止时以 `message.cancelled`
                    结束，两种情况都不会抛错中断连接。

                    `message.created` 里的 `requestId` 就是停止生成所需的参数。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "流式响应，事件序列见描述"),
            @ApiResponse(responseCode = "400", description = "参数校验失败，或消息中没有 user 轮次"),
            @ApiResponse(responseCode = "404", description = "会话不存在，或所选模型已下架"),
            @ApiResponse(responseCode = "401", description = "未提供有效的登录凭证"),
            @ApiResponse(responseCode = "402", description = "New API 账户余额不足"),
            @ApiResponse(responseCode = "503", description = "New API 计费或余额查询暂时不可用")
    })
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatCompletionRequest request,
                             HttpServletRequest httpRequest) {
        // Resolve the account on the request thread: the streaming task runs on a pool thread
        // where the servlet request is no longer guaranteed to be valid.
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        ChatService.PreparedChat prepared = chatService.prepare(request, currentUser);

        SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeout().toMillis());
        chatStreamExecutor.execute(() -> {
            try {
                chatService.stream(prepared, event -> send(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    @Operation(
            summary = "停止生成",
            description = """
                    停止一个正在进行的流式生成。`requestId` 取自 `POST /api/v1/chat/stream` 的
                    `message.created` 事件。

                    取消后上游连接会被真正关闭，而不是只丢弃后续 token；已生成的部分内容会保存为
                    `status=CANCELLED` 的 assistant 消息，并以 `message.cancelled` 事件下发。

                    重复取消返回 `204`：生成结束后的短时间内该 `requestId` 仍被保留，
                    避免用户正好在回答结束时点「停止」却收到错误。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已发出停止信号，或该生成已经结束"),
            @ApiResponse(responseCode = "404", description = "该账号没有此进行中的请求",
                    content = @Content(schema = @Schema(implementation = com.hub.backend.common.ApiError.class)))
    })
    @PostMapping("/requests/{requestId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String requestId, HttpServletRequest httpRequest) {
        chatService.cancelGeneration(
                requestId,
                currentUserResolver.requireAuthenticated(httpRequest).userId());
        return ResponseEntity.noContent().build();
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
            throw new LlmInvocationException("Unable to serialise stream frame " + event.event(), exception);
        }
    }
}
