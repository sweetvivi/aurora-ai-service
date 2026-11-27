package com.hub.backend.modules.document;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.document.dto.DocumentHistoryDetail;
import com.hub.backend.modules.document.dto.DocumentHistorySummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * History of the 「文档处理」 page's tool runs.
 *
 * <p>Separate from {@link DocumentController} because history outlives the document: a deleted
 * document still has entries here, and {@code /documents/{id}} would otherwise have to special
 * case the {@code history} path segment against its own {@code {id}} mapping.</p>
 */
@RestController
@RequestMapping("/api/v1/documents/history")
@Tag(name = "文档处理", description = "上传文档、执行处理动作、基于文档问答与导出")
public class DocumentHistoryController {

    private final DocumentHistoryService history;
    private final CurrentUserResolver currentUserResolver;

    public DocumentHistoryController(DocumentHistoryService history,
                                     CurrentUserResolver currentUserResolver) {
        this.history = history;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "处理历史列表",
            description = """
                    按时间倒序返回当前账号执行过的工具。只返回摘要，不返回结果正文——正文由
                    `GET /documents/history/{id}` 单独取，避免一页拉几百 KB 文本。

                    `documentId` 不传表示全部文档；文档删除后仍可用它的 id 查到历史。
                    `keyword` 同时匹配文档名与工具名，`%` 和 `_` 会被当成普通字符。
                    """)
    @ApiResponse(responseCode = "200", description = "分页列表")
    @GetMapping
    public PageResponse<DocumentHistorySummary> list(
            @Parameter(description = "只看某份文档的历史") @RequestParam(required = false) Long documentId,
            @Parameter(description = "只看某个动作，例如 summary") @RequestParam(required = false) String action,
            @Parameter(description = "按文档名或工具名搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 0 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return history.search(currentUser, documentId, action, keyword, page, size);
    }

    @Operation(summary = "处理历史详情", description = "返回结果正文与产物附件 id，用于把历史结果重新显示在结果卡片里。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "历史详情"),
            @ApiResponse(responseCode = "404", description = "历史不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{historyId}")
    public DocumentHistoryDetail detail(@PathVariable Long historyId, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return history.detail(currentUser, historyId);
    }

    @Operation(summary = "删除一条历史")
    @DeleteMapping("/{historyId}")
    public ResponseEntity<Void> delete(@PathVariable Long historyId, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        history.delete(currentUser, historyId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "清空当前账号的处理历史", description = "只删历史记录，不影响文档与已生成的附件。")
    @DeleteMapping
    public ResponseEntity<Void> clear(HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        history.clear(currentUser);
        return ResponseEntity.noContent().build();
    }
}
