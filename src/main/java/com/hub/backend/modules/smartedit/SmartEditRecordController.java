package com.hub.backend.modules.smartedit;

import com.hub.backend.common.ApiError;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.smartedit.dto.RecordCanvasUpdateRequest;
import com.hub.backend.modules.smartedit.dto.RecordCreateRequest;
import com.hub.backend.modules.smartedit.dto.RecordMessagesUpdateRequest;
import com.hub.backend.modules.smartedit.dto.RecordPageResponse;
import com.hub.backend.modules.smartedit.dto.RecordResponse;
import com.hub.backend.modules.smartedit.dto.RecordTitleUpdateRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Board records for the smart-editing workspace.
 */
@RestController
@RequestMapping("/api/v1/smart-editing")
@Tag(name = "智能修图-记录", description = "画板记录、画布快照与公开示例")
public class SmartEditRecordController {

    private final SmartEditRecordService recordService;
    private final CurrentUserResolver currentUserResolver;

    public SmartEditRecordController(SmartEditRecordService recordService,
                                     CurrentUserResolver currentUserResolver) {
        this.recordService = recordService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "记录列表", description = "返回当前账号的画板记录，页码从 1 开始。")
    @GetMapping("/records")
    public RecordPageResponse list(
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(required = false) Integer rows,
            HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return recordService.list(currentUser, page, rows);
    }

    @Operation(summary = "记录详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "记录详情"),
            @ApiResponse(responseCode = "404", description = "记录不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/records/{id}")
    public RecordResponse detail(@PathVariable Long id, HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return recordService.get(id, currentUser);
    }

    @Operation(summary = "新建记录", description = "标题、画布和消息都可以不传，服务端会写入空画板。")
    @PostMapping("/records")
    public RecordResponse create(
            @RequestBody(required = false) @Valid RecordCreateRequest body,
            HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        return recordService.create(
                currentUser,
                body == null ? new RecordCreateRequest(null, null, null) : body);
    }

    @Operation(summary = "覆盖保存画布")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已保存"),
            @ApiResponse(responseCode = "404", description = "记录不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/records/{id}/canvas")
    public ResponseEntity<Void> updateCanvas(
            @PathVariable Long id,
            @Valid @RequestBody RecordCanvasUpdateRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        recordService.updateCanvas(id, currentUser, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "覆盖保存消息", description = "用完整消息数组覆盖当前记录，并刷新更新时间。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已保存"),
            @ApiResponse(responseCode = "400", description = "messages 不是 JSON 数组",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "记录不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/records/{id}/messages")
    public ResponseEntity<Void> updateMessages(
            @PathVariable Long id,
            @Valid @RequestBody RecordMessagesUpdateRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        recordService.updateMessages(id, currentUser, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "修改项目名称")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已修改"),
            @ApiResponse(responseCode = "400", description = "title 为空或超过 255 个字符",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "记录不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/records/{id}/title")
    public ResponseEntity<Void> updateTitle(
            @PathVariable Long id,
            @Valid @RequestBody RecordTitleUpdateRequest request,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        recordService.updateTitle(id, currentUser, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "删除记录")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "404", description = "记录不存在或不属于当前账号",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/records/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(request);
        recordService.delete(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "公开示例画板", description = "该接口不需要账号信息。")
    @GetMapping("/demos")
    public List<RecordResponse> demos() {
        return recordService.demos();
    }
}
