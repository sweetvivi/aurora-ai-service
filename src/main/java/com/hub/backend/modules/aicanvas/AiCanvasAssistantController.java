package com.hub.backend.modules.aicanvas;

import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatMessageListResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatSessionResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.CompositeRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.DescribeImageRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ImageDescriptionResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ImageUrlResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.OptimizeStoryRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.PromptOptimizeRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.PromptResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ScriptsRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ScriptsResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.StoryRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.StoryResponse;
import com.hub.backend.modules.auth.CurrentUserResolver;
import com.hub.backend.modules.auth.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/ai-canvas", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI 画布-助手", description = "提示词、灵感画板和分镜接口")
public class AiCanvasAssistantController {

    private final AiCanvasAssistantService assistantService;
    private final CurrentUserResolver currentUserResolver;

    public AiCanvasAssistantController(AiCanvasAssistantService assistantService,
                                       CurrentUserResolver currentUserResolver) {
        this.assistantService = assistantService;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(summary = "优化画布提示词")
    @PostMapping("/prompt/optimize")
    public PromptResponse optimizePrompt(@Valid @RequestBody PromptOptimizeRequest body,
                                         HttpServletRequest request) {
        return assistantService.optimizePrompt(currentUser(request), body);
    }

    @Operation(summary = "多模态描述图片")
    @PostMapping("/prompt/describe-image")
    public ImageDescriptionResponse describeImage(@Valid @RequestBody DescribeImageRequest body,
                                                  HttpServletRequest request) {
        return assistantService.describeImage(currentUser(request), body);
    }

    @Operation(summary = "灵感画板会话列表")
    @GetMapping("/chat/sessions")
    public List<ChatSessionResponse> chatSessions(HttpServletRequest request) {
        return assistantService.chatSessions(currentUser(request));
    }

    @Operation(summary = "创建灵感画板会话")
    @PostMapping("/chat/sessions")
    public ChatSessionResponse createChatSession(HttpServletRequest request) {
        return assistantService.createChatSession(currentUser(request));
    }

    @Operation(summary = "读取灵感画板消息")
    @GetMapping("/chat/sessions/{id}")
    public ChatMessageListResponse chatMessages(@PathVariable String id,
                                                HttpServletRequest request) {
        return assistantService.chatMessages(currentUser(request), id);
    }

    @Operation(summary = "删除灵感画板会话")
    @DeleteMapping("/chat/sessions/{id}")
    public ResponseEntity<Void> deleteChatSession(@PathVariable String id,
                                                  HttpServletRequest request) {
        assistantService.deleteChatSession(currentUser(request), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "发送灵感画板消息")
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest body, HttpServletRequest request) {
        return assistantService.sendChatMessage(currentUser(request), body);
    }

    @Operation(summary = "构思故事")
    @PostMapping("/storyboard/brainstorm-story")
    public StoryResponse brainstormStory(@Valid @RequestBody StoryRequest body,
                                         HttpServletRequest request) {
        return assistantService.brainstormStory(currentUser(request), body);
    }

    @Operation(summary = "优化故事")
    @PostMapping("/storyboard/optimize-story")
    public StoryResponse optimizeStory(@Valid @RequestBody OptimizeStoryRequest body,
                                       HttpServletRequest request) {
        return assistantService.optimizeStory(currentUser(request), body);
    }

    @Operation(summary = "生成分镜脚本")
    @PostMapping("/storyboard/generate-scripts")
    public ScriptsResponse generateScripts(@Valid @RequestBody ScriptsRequest body,
                                           HttpServletRequest request) {
        return assistantService.generateScripts(currentUser(request), body);
    }

    @Operation(summary = "合成分镜故事板")
    @PostMapping("/storyboard/generate-composite")
    public ImageUrlResponse generateComposite(@Valid @RequestBody CompositeRequest body,
                                              HttpServletRequest request) {
        return assistantService.generateComposite(currentUser(request), body);
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        return currentUserResolver.requireAuthenticated(request);
    }
}
