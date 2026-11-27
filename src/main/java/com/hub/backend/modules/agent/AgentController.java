package com.hub.backend.modules.agent;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.modules.agent.dto.AgentCategoryResponse;
import com.hub.backend.modules.agent.dto.AgentFavoriteResponse;
import com.hub.backend.modules.agent.dto.AgentResponse;
import com.hub.backend.modules.agent.dto.AgentUserRequest;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.appconfig.ChannelResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "智能体", description = "智能体目录、创建维护、详情与收藏")
public class AgentController {

    private final AgentService agentService;
    private final CurrentUserResolver currentUserResolver;
    private final ChannelResolver channelResolver;

    public AgentController(AgentService agentService,
                           CurrentUserResolver currentUserResolver,
                           ChannelResolver channelResolver) {
        this.agentService = agentService;
        this.currentUserResolver = currentUserResolver;
        this.channelResolver = channelResolver;
    }

    @Operation(summary = "智能体列表")
    @GetMapping
    public PageResponse<AgentResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long excludeCategoryId,
            @RequestParam(required = false) String childClassify,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false") boolean favorite,
            @RequestParam(required = false) Boolean hot,
            @RequestParam(required = false) Integer scope,
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.resolveOptional(httpRequest);
        if (mine || favorite) {
            currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        }
        if (currentUser == null) {
            return agentService.listPublic(channelResolver.resolve(httpRequest).getId(), page, size,
                    categoryId, excludeCategoryId, childClassify, keyword, hot, scope, sort);
        }
        return agentService.list(currentUser, page, size, categoryId, excludeCategoryId, childClassify, keyword,
                mine, favorite, hot, scope, sort);
    }

    @Operation(summary = "我创建的智能体")
    @GetMapping("/mine")
    public PageResponse<AgentResponse> listMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long excludeCategoryId,
            @RequestParam(required = false) String childClassify,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean hot,
            @RequestParam(required = false) Integer scope,
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.list(currentUser, page, size, categoryId, excludeCategoryId, childClassify, keyword,
                true, false, hot, scope, sort);
    }

    @Operation(summary = "智能体分类")
    @GetMapping("/categories")
    public List<AgentCategoryResponse> categories(HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.resolveOptional(httpRequest);
        Long siteId = currentUser == null
                ? channelResolver.resolve(httpRequest).getId()
                : currentUser.siteId();
        return agentService.listCategories(siteId);
    }

    @Operation(summary = "智能体详情")
    @GetMapping("/{reference}")
    public AgentResponse detail(@PathVariable String reference, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.resolveOptional(httpRequest);
        if (currentUser == null) {
            return agentService.publicDetail(reference, channelResolver.resolve(httpRequest).getId());
        }
        return agentService.detail(reference, currentUser);
    }

    @Operation(summary = "创建智能体")
    @ApiResponse(responseCode = "201", description = "已创建")
    @PostMapping
    public ResponseEntity<AgentResponse> create(@Valid @RequestBody AgentUserRequest request,
                                                HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agentService.create(request, currentUser));
    }

    @Operation(summary = "更新智能体")
    @PatchMapping("/{id}")
    public AgentResponse update(@PathVariable Long id,
                                @Valid @RequestBody AgentUserRequest request,
                                HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.update(id, request, currentUser);
    }

    @Operation(summary = "删除智能体")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        agentService.delete(id, currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "收藏智能体")
    @PostMapping("/{id}/favorite")
    public AgentFavoriteResponse favorite(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.addFavorite(id, currentUser);
    }

    @Operation(summary = "取消收藏智能体")
    @DeleteMapping("/{id}/favorite")
    public AgentFavoriteResponse unfavorite(@PathVariable Long id, HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        return agentService.removeFavorite(id, currentUser);
    }
}
