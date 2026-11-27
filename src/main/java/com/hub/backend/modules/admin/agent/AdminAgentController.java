package com.hub.backend.modules.admin.agent;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import com.hub.backend.modules.agent.AgentService;
import com.hub.backend.modules.agent.dto.AgentAdminRequest;
import com.hub.backend.modules.agent.dto.AgentCategoryResponse;
import com.hub.backend.modules.agent.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * Management CRUD for the agent catalogue.
 *
 * <p>The public API deliberately scopes writes to the current user. The console needs to maintain
 * official agents and to inspect retired rows, so this controller uses a separate admin view and
 * write path while keeping the same soft-delete behaviour as the public endpoint.</p>
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "AGENT")
@RestController
@RequestMapping("/api/admin/v1/agents")
@Tag(name = "智能体管理", description = "后台智能体增删改查")
public class AdminAgentController {

    private final AgentService agentService;
    private final AdminGuard adminGuard;

    public AdminAgentController(AgentService agentService, AdminGuard adminGuard) {
        this.agentService = agentService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "智能体列表",
            description = """
                    后台列表默认返回 ACTIVE、UNPUBLISHED 与 DELETED 数据，便于管理和恢复。
                    `status`、`visibility`、`publishStatus` 等筛选在服务端完成。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "智能体分页列表"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public PageResponse<AgentResponse> list(
            @Parameter(description = "页码，从 0 开始")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，最大 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "站点 ID")
            @RequestParam(required = false) Long siteId,
            @Parameter(description = "分类 ID")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "归属用户 ID")
            @RequestParam(required = false) Long ownerUserId,
            @Parameter(description = "类型：OFFICIAL / CUSTOM")
            @RequestParam(required = false) String agentType,
            @Parameter(description = "状态：ACTIVE / UNPUBLISHED / DELETED；不传表示全部")
            @RequestParam(required = false) String status,
            @Parameter(description = "可见性：PRIVATE / PUBLIC")
            @RequestParam(required = false) String visibility,
            @Parameter(description = "发布状态：DRAFT / PUBLISHED / DELETED")
            @RequestParam(required = false) String publishStatus,
            @Parameter(description = "名称、简介、KEY、SN、分类或来源路径关键字")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "是否热门")
            @RequestParam(required = false) Boolean hot,
            @Parameter(description = "排序：updated（默认）、sort、score、hot、created")
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return agentService.listAdmin(
                page, size, siteId, categoryId, ownerUserId, agentType, status,
                visibility, publishStatus, keyword, hot, sort);
    }

    @Operation(summary = "智能体分类",
            description = "返回 ACTIVE 分类；传 siteId 时只返回该站点，否则返回全部分类。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "智能体分类列表"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/categories")
    public List<AgentCategoryResponse> categories(
            @Parameter(description = "站点 ID；不传表示全部站点")
            @RequestParam(required = false) Long siteId,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return agentService.listAdminCategories(siteId);
    }

    @Operation(summary = "智能体详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "智能体详情"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "智能体不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public AgentResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return agentService.detailAdmin(id);
    }

    @Operation(summary = "创建智能体",
            description = """
                    `name` 与 `systemPrompt` 为必填。`ownerUserId` 留空时创建平台官方智能体；
                    填写时默认创建 CUSTOM 智能体，也可以用 `agentType` 显式覆盖。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "已创建"),
            @ApiResponse(responseCode = "400", description = "字段校验失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    public ResponseEntity<AgentResponse> create(@Valid @RequestBody AgentAdminRequest request,
                                                HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agentService.createAdmin(request));
    }

    @Operation(summary = "更新智能体",
            description = """
                    按字段更新：请求中为 null 的字段保持原值，集合字段传 `[]` 表示清空。
                    `status=UNPUBLISHED` 仅下架并保留发布状态；`status=DELETED` 执行软删除；
                    `status=ACTIVE` 恢复上架，软删除恢复时发布状态从 DELETED 重置为 DRAFT。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已更新"),
            @ApiResponse(responseCode = "400", description = "字段校验失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "智能体不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}")
    public AgentResponse update(@PathVariable Long id,
                                @Valid @RequestBody AgentAdminRequest request,
                                HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return agentService.updateAdmin(id, request);
    }

    @Operation(summary = "下架智能体",
            description = "将智能体从前台目录下架；数据与历史会话保留，后台可恢复。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已下架"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "智能体不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/unpublish")
    public AgentResponse unpublish(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return agentService.unpublishAdmin(id);
    }

    @Operation(summary = "删除智能体",
            description = "软删除：后台仍可查询并恢复，智能体历史会话不会被移除。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "已删除"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "智能体不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        agentService.deleteAdmin(id);
        return ResponseEntity.noContent().build();
    }
}
