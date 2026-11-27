package com.hub.backend.modules.conversation;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.modules.agent.ChatAttachmentLoader;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.conversation.dto.ChatMessageCursorResponse;
import com.hub.backend.modules.conversation.dto.ChatMessageResponse;
import com.hub.backend.modules.conversation.dto.ConversationBatchDeleteRequest;
import com.hub.backend.modules.conversation.dto.ConversationBatchDeleteResponse;
import com.hub.backend.modules.conversation.dto.ConversationCreateRequest;
import com.hub.backend.modules.conversation.dto.ConversationRenameRequest;
import com.hub.backend.modules.conversation.dto.ConversationSummaryResponse;
import com.hub.backend.modules.conversation.dto.FailedTurnRequest;
import com.hub.backend.modules.conversation.dto.MessageAppendRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversation history of the current account.
 *
 * <p>Every endpoint is scoped by the resolved account: a thread id belonging to somebody else is
 * reported as {@code 404}, never as {@code 403}, so the API does not leak the existence of other
 * accounts' data.</p>
 */
@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "会话管理", description = "按账号查询历史会话与消息记录")
public class ConversationController {

    private final ConversationService conversationService;
    private final CurrentUserResolver currentUserResolver;
    private final ChatAttachmentLoader attachmentLoader;

    public ConversationController(ConversationService conversationService,
                                  CurrentUserResolver currentUserResolver,
                                  ChatAttachmentLoader attachmentLoader) {
        this.conversationService = conversationService;
        this.currentUserResolver = currentUserResolver;
        this.attachmentLoader = attachmentLoader;
    }

    @Operation(summary = "历史会话列表",
            description = """
                    按最后更新时间倒序返回当前账号的会话，分页参数从 0 开始。

                    可用 `type` 只看某一类：`CHAT`（模型对话）或 `PAINTING`（绘画作品）。
                    不传则返回全部（管理端/分享等场景）。绘画页把每张图存成一个会话，
                    聊天侧传 `CHAT` 即可把作品容器排除在对话记录之外。
                    """)
    @ApiResponse(responseCode = "200", description = "会话分页")
    @GetMapping
    public PageResponse<ConversationSummaryResponse> list(
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer size,
            @Parameter(description = "会话类型：CHAT / PAINTING，缺省返回全部")
            @RequestParam(required = false) String type,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.listConversations(currentUser.userId(), page, size, type);
    }

    @Operation(summary = "会话详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "会话摘要"),
            @ApiResponse(responseCode = "404", description = "会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public ConversationSummaryResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.getConversation(id, currentUser.userId());
    }

    @Operation(summary = "消息记录（游标分页）",
            description = """
                    按会话内序号升序返回消息。首次请求不传游标，返回**最新一页**（适合打开会话）。
                    上滑加载更早消息时传 `before=prevCursor`；需要更新于当前页的消息时传
                    `cursor=nextCursor`。`hasOlder=false` / `hasMore=false` 分别表示到头 / 到尾。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "消息分页"),
            @ApiResponse(responseCode = "404", description = "会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}/messages")
    public ChatMessageCursorResponse messages(
            @PathVariable Long id,
            @Parameter(description = "加载比该 id 更新的消息（向末尾翻）")
            @RequestParam(required = false) Long cursor,
            @Parameter(description = "加载比该 id 更早的消息（向开头翻）；优先于 cursor")
            @RequestParam(required = false) Long before,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.listMessages(id, currentUser.userId(), cursor, before, size);
    }

    @Operation(summary = "创建会话",
            description = """
                    显式创建一个空会话。对话接口本身也会在未传 `conversationId` 时自动建会话，
                    这里额外提供独立入口，便于先把会话建好再逐条写入消息。

                    不带 `title` 时，会话标题由首条用户消息自动生成。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已创建的会话"),
            @ApiResponse(responseCode = "400", description = "标题超长",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ConversationSummaryResponse create(
            @RequestBody(required = false) @Valid ConversationCreateRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        String title = request == null ? null : request.titleOrNull();
        String conversationType = request == null ? null : request.conversationTypeOrNull();
        return conversationService.createConversation(currentUser.userId(), title, conversationType);
    }

    @Operation(summary = "追加消息（不触发生成）",
            description = """
                    往会话里追加一条用户消息并落库，**不调用模型**。

                    需要模型回答时走 `POST /api/v1/chat/stream`（或 `/completions`）并带上
                    `conversationId`；这个接口只负责记录消息本身。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已追加的消息"),
            @ApiResponse(responseCode = "400", description = "内容为空或超长",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/messages")
    public ChatMessageResponse appendMessage(
            @PathVariable Long id,
            @Valid @RequestBody MessageAppendRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.appendUserMessageTo(id, currentUser.userId(), request.content());
    }

    @Operation(summary = "激活消息版本",
            description = """
                    将指定消息设为其所在 turn group 的当前版本（`active=true`），同组其他版本软隐藏。
                    用于前端 `1/N` 版本切换；不会恢复被后续编辑截断的更晚轮次。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已激活的消息"),
            @ApiResponse(responseCode = "404", description = "会话或消息不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/messages/{messageId}/activate")
    public ChatMessageResponse activateMessage(
            @PathVariable Long id,
            @PathVariable Long messageId,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.activateMessage(id, currentUser.userId(), messageId);
    }

    @Operation(summary = "记录失败轮次",
            description = """
                    在会话中追加一条用户消息与一条 `FAILED` 的 assistant 消息，**不调用模型**。

                    用于前端已预创建空会话、但生成在落库前就失败（例如生图失败、流式请求未发出）的情况，
                    保证历史列表里的会话点开后仍能进入对话页，而不是空白欢迎页。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新后的会话摘要"),
            @ApiResponse(responseCode = "400", description = "内容为空或超长",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/failed-turn")
    public ConversationSummaryResponse recordFailedTurn(
            @PathVariable Long id,
            @Valid @RequestBody FailedTurnRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.recordFailedTurn(
                id,
                currentUser.userId(),
                request.content(),
                request.errorMessage(),
                attachmentLoader.refsFor(currentUser, request.attachmentIds()));
    }

    @Operation(summary = "批量删除会话",
            description = """
                    一次软删除多个会话，替代逐个调用 `DELETE /{id}`。单次最多 100 个。

                    返回 `requested` / `deleted` / `skipped` 三个计数；`skipped` 混合了「不存在」
                    「不属于当前账号」「已删除」三种情况且不再细分，因此无法借此探测他人会话是否存在。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "批量删除结果"),
            @ApiResponse(responseCode = "400", description = "ids 为空或超过 100 个",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/batch-delete")
    public ConversationBatchDeleteResponse batchDelete(
            @Valid @RequestBody ConversationBatchDeleteRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.deleteConversations(currentUser.userId(), request.ids());
    }

    @Operation(summary = "重命名会话")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新后的会话摘要"),
            @ApiResponse(responseCode = "400", description = "标题为空或超长",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PatchMapping("/{id}")
    public ConversationSummaryResponse rename(@PathVariable Long id,
                                              @Valid @RequestBody ConversationRenameRequest request,
                                              HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return conversationService.renameConversation(id, currentUser.userId(), request.title().strip());
    }

    @Operation(summary = "删除会话",
            description = "软删除：会话从列表消失，但消息记录保留，便于审计与恢复。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "404", description = "会话不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        conversationService.deleteConversation(id, currentUser.userId());
        return ResponseEntity.noContent().build();
    }
}
