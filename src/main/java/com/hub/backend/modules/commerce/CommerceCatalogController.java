package com.hub.backend.modules.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.config.properties.ChatProperties;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.chat.dto.ChatStreamEvent;
import com.hub.backend.modules.commerce.dto.CommerceCatalogResponse;
import com.hub.backend.modules.commerce.dto.CommerceHistoryResponse;
import com.hub.backend.modules.commerce.dto.CommerceToolResponse;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/commerce")
@Tag(name = "电商工具", description = "电商工具目录与工作台数据")
public class CommerceCatalogController {

    private final CommerceCatalogService commerceCatalogService;
    private final CommerceGenerationHistoryService historyService;
    private final CommerceComposeChatService composeChatService;
    private final ChannelResolver channelResolver;
    private final CurrentUserResolver currentUserResolver;
    private final ChatProperties chatProperties;
    private final AsyncTaskExecutor chatStreamExecutor;
    private final ObjectMapper objectMapper;

    public CommerceCatalogController(CommerceCatalogService commerceCatalogService,
                                     CommerceGenerationHistoryService historyService,
                                     CommerceComposeChatService composeChatService,
                                     ChannelResolver channelResolver,
                                     CurrentUserResolver currentUserResolver,
                                     ChatProperties chatProperties,
                                     @Qualifier("chatStreamExecutor") AsyncTaskExecutor chatStreamExecutor,
                                     ObjectMapper objectMapper) {
        this.commerceCatalogService = commerceCatalogService;
        this.historyService = historyService;
        this.composeChatService = composeChatService;
        this.channelResolver = channelResolver;
        this.currentUserResolver = currentUserResolver;
        this.chatProperties = chatProperties;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "工具目录",
            description = """
                    按分类返回当前站点启用的工具，供 `/e-commerce` 工作台与 `/painting`
                    的图片创作工具库渲染。两者共用同一张 commerce_tools，靠 `scene` 区分：

                    - `commerce`（默认）电商工具，`/e-commerce` 与超级智能体的表单面板
                    - `painting` 绘画工具，`/painting` 点工具分类后进来的库""")
    @GetMapping("/catalog")
    public CommerceCatalogResponse catalog(@RequestParam(required = false) String scene,
                                           HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return commerceCatalogService.catalog(site.getId(), resolveScene(scene));
    }

    /**
     * 场景缺省为电商：老调用方（`/e-commerce`、智能体面板）不传参，行为保持不变。
     *
     * <p>非法值直接 400 而不是静默兜底——写错场景名本该立刻发现，
     * 悄悄退回电商会让人以为「绘画工具没种进去」。</p>
     */
    private String resolveScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return CommerceToolEntity.SCENE_COMMERCE;
        }
        String trimmed = scene.trim();
        if (!CommerceToolEntity.SCENE_COMMERCE.equals(trimmed)
                && !CommerceToolEntity.SCENE_PAINTING.equals(trimmed)) {
            throw new InvalidRequestException("scene 取值非法：" + scene);
        }
        return trimmed;
    }

    @Operation(summary = "单个工具",
            description = """
                    按 toolKey 取一个启用中的工具，返回结构与目录里的一项相同。
                    智能体详情页的「表单形态」用它渲染左侧面板 —— 只取引用的那一个，
                    不必为了一个工具把整份目录（56 个工具 + schema）拉下来。""")
    @GetMapping("/tools/{toolKey}")
    public CommerceToolResponse tool(@PathVariable String toolKey, HttpServletRequest httpRequest) {
        AppSiteEntity site = channelResolver.resolve(httpRequest);
        return commerceCatalogService.tool(site.getId(), toolKey);
    }

    @Operation(summary = "我的电商生成历史")
    @GetMapping("/history")
    public PageResponse<CommerceHistoryResponse> history(@RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size,
                                                         HttpServletRequest httpRequest) {
        return historyService.list(currentUserResolver.requireAuthenticated(httpRequest), page, size);
    }

    @Operation(summary = "删除电商生成历史")
    @DeleteMapping("/history/{id}")
    public void deleteHistory(@PathVariable Long id, HttpServletRequest httpRequest) {
        historyService.delete(currentUserResolver.requireAuthenticated(httpRequest), id);
    }

    @Operation(summary = "清空我的电商生成历史")
    @DeleteMapping("/history")
    public void clearHistory(HttpServletRequest httpRequest) {
        historyService.clear(currentUserResolver.requireAuthenticated(httpRequest));
    }

    @Operation(
            summary = "工具生成（SSE 流式）",
            description = """
                    multipart 字段为 images（1-4 张）、prompt，可选 size 与 params。
                    params 是表单选项的快照 `[{"label":"成套类型","value":"美妆个护套装"}]`，
                    后端会把它写进系统提示词，让模型知道用户勾了什么。
                    响应事件与智能体对话一致：message.delta 是模型的说明，tool.call / tool.result
                    是出图过程，图片地址在 tool.result 的 meta.images 里。""")
    @PostMapping(value = "/tools/{toolKey}/compose/stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter composeStream(@PathVariable String toolKey,
                                    @RequestParam("images") List<MultipartFile> images,
                                    @RequestParam("prompt") String prompt,
                                    @RequestParam(required = false) String size,
                                    @RequestParam(required = false) String params,
                                    @RequestParam(required = false) String extra,
                                    HttpServletRequest httpRequest) {
        CurrentUser currentUser = currentUserResolver.requireAuthenticated(httpRequest);
        SseEmitter emitter = new SseEmitter(chatProperties.getStreamTimeout().toMillis());
        chatStreamExecutor.execute(() -> {
            try {
                composeChatService.compose(
                        currentUser.siteId(), currentUser, toolKey, images, prompt, size, params, extra,
                        event -> send(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                // 已经建立 SSE 连接后再抛异常，前端只会看到连接断开；补一帧失败事件说明原因
                try {
                    send(emitter, ChatStreamEvent.failed(
                            exception.getMessage() == null ? "生成失败" : exception.getMessage()));
                } catch (RuntimeException ignored) {
                    /* 连接已断，无处可写 */
                }
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
            throw new IllegalStateException("Unable to serialise stream event", exception);
        }
    }
}
