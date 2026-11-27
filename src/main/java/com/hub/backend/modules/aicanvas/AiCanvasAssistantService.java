package com.hub.backend.modules.aicanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.infra.llm.dto.LlmChatMessage;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatMessageListResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatMessageResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ChatSessionResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.CharacterDescription;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.CompositeRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.DescribeImageRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ImageDescriptionResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ImageUrlResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.OptimizeStoryRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.PromptOptimizeRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.PromptResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ScriptResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ScriptsRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.ScriptsResponse;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.StoryRequest;
import com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.StoryResponse;
import com.hub.backend.modules.aicanvas.entity.AiCanvasChatMessageEntity;
import com.hub.backend.modules.aicanvas.entity.AiCanvasChatSessionEntity;
import com.hub.backend.modules.aicanvas.repository.AiCanvasChatMessageRepository;
import com.hub.backend.modules.aicanvas.repository.AiCanvasChatSessionRepository;
import com.hub.backend.modules.auth.dto.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.hub.backend.modules.aicanvas.dto.AiCanvasDtos.toUtc;

/**
 * Prompting, inspiration-board chat and storyboard workflows for AI Canvas.
 */
@Service
public class AiCanvasAssistantService {

    private static final String NEW_CHAT_TITLE = "新对话";
    private static final int MAX_HISTORY_MESSAGES = 24;
    private static final int MAX_MEDIA_IMAGES = 4;

    private final AiCanvasModelCaller modelCaller;
    private final AiCanvasImageResolver imageResolver;
    private final AiCanvasGenerationService generationService;
    private final AiCanvasChatSessionRepository sessionRepository;
    private final AiCanvasChatMessageRepository messageRepository;
    private final AiCanvasJsonCodec jsonCodec;
    private final ObjectMapper objectMapper;

    public AiCanvasAssistantService(AiCanvasModelCaller modelCaller,
                                    AiCanvasImageResolver imageResolver,
                                    AiCanvasGenerationService generationService,
                                    AiCanvasChatSessionRepository sessionRepository,
                                    AiCanvasChatMessageRepository messageRepository,
                                    AiCanvasJsonCodec jsonCodec,
                                    ObjectMapper objectMapper) {
        this.modelCaller = modelCaller;
        this.imageResolver = imageResolver;
        this.generationService = generationService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.jsonCodec = jsonCodec;
        this.objectMapper = objectMapper;
    }

    public PromptResponse optimizePrompt(CurrentUser currentUser, PromptOptimizeRequest request) {
        AiCanvasModelCaller.PreparedCompletion completion = modelCaller.prepare(currentUser);
        String optimized = modelCaller.complete(
                completion,
                """
                You improve prompts for image and video generation. Preserve the user's intent,
                add concrete subject, composition, lighting, style and camera details, and return
                only the improved prompt without labels or Markdown.
                """,
                request.prompt().strip(),
                1200);
        return new PromptResponse(optimized);
    }

    public ImageDescriptionResponse describeImage(CurrentUser currentUser,
                                                  DescribeImageRequest request) {
        AiCanvasModelCaller.PreparedCompletion completion = modelCaller.prepare(currentUser);
        AiCanvasImageResolver.LoadedImage image = imageResolver.load(request.imageUrl(), currentUser);
        String instruction = request.prompt() == null || request.prompt().isBlank()
                ? "描述画面，并给出可直接复用的生成提示词。"
                : request.prompt().strip();
        String description = modelCaller.complete(
                completion,
                """
                You are a visual analyst. Inspect the supplied image and answer in the user's
                language. Be concrete about subject, scene, composition, lighting, color, style
                and text. Return only the requested description, without Markdown fences.
                """,
                List.of(LlmChatMessage.user(
                        instruction,
                        List.of(new LlmImageAttachment(image.mimeType(), image.content())))),
                1600);
        return new ImageDescriptionResponse(description);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> chatSessions(CurrentUser currentUser) {
        return sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(
                        currentUser.userId(), AiCanvasChatSessionEntity.STATUS_ACTIVE)
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional
    public ChatSessionResponse createChatSession(CurrentUser currentUser) {
        AiCanvasChatSessionEntity session = sessionRepository.saveAndFlush(
                new AiCanvasChatSessionEntity(
                        AiCanvasJsonCodec.newId("chat"),
                        currentUser.userId(),
                        NEW_CHAT_TITLE));
        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public ChatMessageListResponse chatMessages(CurrentUser currentUser, String sessionId) {
        requireSession(currentUser, sessionId);
        List<ChatMessageResponse> messages = messageRepository
                .findBySessionIdOrderBySequenceNoAsc(sessionId)
                .stream()
                .map(this::toMessageResponse)
                .toList();
        return new ChatMessageListResponse(messages);
    }

    @Transactional
    public void deleteChatSession(CurrentUser currentUser, String sessionId) {
        requireSession(currentUser, sessionId);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    @Transactional
    public ChatResponse sendChatMessage(CurrentUser currentUser, ChatRequest request) {
        AiCanvasModelCaller.PreparedCompletion completion = modelCaller.prepare(currentUser);
        AiCanvasChatSessionEntity session = requireSession(currentUser, request.sessionId());
        JsonNode refs = normalizeMedia(request.media());
        List<AiCanvasChatMessageEntity> history =
                messageRepository.findBySessionIdOrderBySequenceNoAsc(session.getId());
        List<LlmChatMessage> messages = history.stream()
                .filter(message -> message.getRole().equals(LlmChatMessage.ROLE_USER)
                        || message.getRole().equals(LlmChatMessage.ROLE_ASSISTANT))
                .skip(Math.max(0, history.size() - MAX_HISTORY_MESSAGES))
                .map(message -> new LlmChatMessage(message.getRole(), message.getContent()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        messages.add(LlmChatMessage.user(
                request.message().strip(),
                mediaImages(currentUser, refs)));

        String reply = modelCaller.complete(
                completion,
                """
                You are the assistant inside an AI visual canvas. Help the user think through
                images, video shots, prompts and storyboards. Be concise and concrete. Refer to
                the supplied media when present. Answer in the user's language without Markdown
                fences.
                """,
                messages,
                2000);

        int sequence = Math.toIntExact(messageRepository.countBySessionId(session.getId()) + 1);
        messageRepository.save(new AiCanvasChatMessageEntity(
                AiCanvasJsonCodec.newId("msg"),
                session.getId(),
                sequence,
                LlmChatMessage.ROLE_USER,
                request.message().strip(),
                jsonCodec.write(refs)));
        messageRepository.save(new AiCanvasChatMessageEntity(
                AiCanvasJsonCodec.newId("msg"),
                session.getId(),
                sequence + 1,
                LlmChatMessage.ROLE_ASSISTANT,
                reply,
                "[]"));

        String topic = topicOf(request.message());
        if (history.isEmpty() || NEW_CHAT_TITLE.equals(session.getTitle())) {
            session.updateTitle(topic);
        }
        session.recordMessage();
        session.recordMessage();
        return new ChatResponse(reply, topic);
    }

    public StoryResponse brainstormStory(CurrentUser currentUser, StoryRequest request) {
        AiCanvasModelCaller.PreparedCompletion completion = modelCaller.prepare(currentUser);
        String prompt = """
                根据以下角色设定构思一个完整、可拍摄、适合分镜拆解的短故事。
                请包含明确的开端、冲突、转折和收束，只返回故事正文。

                角色设定：
                %s
                """.formatted(characterText(request.characterDescriptions()));
        String story = modelCaller.complete(
                completion,
                "You are a screenwriter. Return only the story text in the user's language.",
                List.of(LlmChatMessage.user(
                        prompt, referenceImages(currentUser, request.referenceImages()))),
                2400);
        return new StoryResponse(story);
    }

    public StoryResponse optimizeStory(CurrentUser currentUser, OptimizeStoryRequest request) {
        AiCanvasModelCaller.PreparedCompletion completion = modelCaller.prepare(currentUser);
        String names = request.characterNames() == null || request.characterNames().isEmpty()
                ? "未提供"
                : String.join("、", request.characterNames());
        String prompt = """
                润色下面的故事，强化画面感、节奏、人物动机和镜头可执行性，但不要改变核心情节。
                角色名：%s

                原故事：
                %s
                """.formatted(names, request.story().strip());
        String story = modelCaller.complete(
                completion,
                "You are a screenwriter. Return only the polished story in the user's language.",
                prompt,
                2800);
        return new StoryResponse(story);
    }

    public ScriptsResponse generateScripts(CurrentUser currentUser, ScriptsRequest request) {
        AiCanvasModelCaller.PreparedCompletion completion = modelCaller.prepare(currentUser);
        String prompt = """
                将下面的故事拆解为 %d 个连续分镜脚本。必须只输出一个 JSON 对象，结构如下：
                {
                  "scripts": [
                    {
                      "sceneNumber": 1,
                      "description": "画面内容",
                      "cameraAngle": "镜头角度",
                      "cameraMovement": "镜头运动，例如 缓推 / 横移 / 升降 / 手持跟拍",
                      "lighting": "光线与色调，例如 黄昏侧逆光",
                      "mood": "情绪",
                      "prompt": "用于生成该镜头的详细提示词"
                    }
                  ],
                  "styleAnchor": "统一视觉风格锚点",
                  "characterDNA": {}
                }
                scripts 必须恰好有 %d 项，sceneNumber 从 1 连续递增。

                故事：
                %s

                角色设定：
                %s
                """.formatted(
                request.sceneCount(),
                request.sceneCount(),
                request.story().strip(),
                characterText(request.characterDescriptions()));
        String content = modelCaller.complete(
                completion,
                """
                You are a storyboard director. Follow the requested JSON schema exactly.
                Return JSON only, with no Markdown fence or commentary. Use the user's language
                for descriptive values.
                """,
                List.of(LlmChatMessage.user(
                        prompt, referenceImages(currentUser, request.referenceImages()))),
                5000);
        return parseScripts(content, request.sceneCount());
    }

    public ImageUrlResponse generateComposite(CurrentUser currentUser, CompositeRequest request) {
        AiCanvasJsonCodec.requireArray(request.scripts(), "scripts");
        ProviderCredentials credentials = generationService.prepareImage(currentUser);
        String prompt = """
                生成一张统一、可用于故事板浏览的拼图。保持角色、服装、色彩和视觉风格一致。
                场景数：%s
                统一风格：%s
                角色 DNA：%s

                分镜脚本：
                %s
                """.formatted(
                request.sceneCount() == null ? request.scripts().size() : request.sceneCount(),
                valueOrEmpty(request.styleAnchor()),
                request.characterDNA() == null ? "{}" : request.characterDNA().toString(),
                request.scripts().toString());
        String imageUrl = generationService.generateImageFromReferences(
                        currentUser,
                        prompt,
                        null,
                        "Auto",
                        "1K",
                        loadReferenceImages(currentUser, request.referenceImages()),
                        credentials)
                .url();
        return new ImageUrlResponse(imageUrl);
    }

    private AiCanvasChatSessionEntity requireSession(CurrentUser currentUser, String sessionId) {
        return sessionRepository.findByIdAndUserIdAndStatus(
                        sessionId,
                        currentUser.userId(),
                        AiCanvasChatSessionEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "AI Canvas chat '%s' was not found".formatted(sessionId)));
    }

    private ChatSessionResponse toSessionResponse(AiCanvasChatSessionEntity session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getTitle(),
                toUtc(session.getCreatedAt()),
                toUtc(session.getUpdatedAt()),
                session.getMessageCount());
    }

    private ChatMessageResponse toMessageResponse(AiCanvasChatMessageEntity message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getRole(),
                message.getContent(),
                toUtc(message.getCreatedAt()),
                jsonCodec.read(message.getRefsJson()));
    }

    private JsonNode normalizeMedia(JsonNode media) {
        if (media == null || media.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!media.isArray()) {
            throw new InvalidRequestException("media 必须是 JSON 数组");
        }
        return media;
    }

    private List<LlmImageAttachment> mediaImages(CurrentUser currentUser, JsonNode media) {
        List<LlmImageAttachment> images = new ArrayList<>();
        for (JsonNode item : media) {
            if (images.size() >= MAX_MEDIA_IMAGES) {
                break;
            }
            String type = item.path("type").asText("");
            String url = item.path("url").asText("");
            if (type.equalsIgnoreCase("Image") && !url.isBlank()) {
                AiCanvasImageResolver.LoadedImage image = imageResolver.load(url, currentUser);
                images.add(new LlmImageAttachment(image.mimeType(), image.content()));
            }
        }
        return images;
    }

    private List<LlmImageAttachment> referenceImages(CurrentUser currentUser, JsonNode references) {
        return loadReferenceImages(currentUser, references).stream()
                .map(image -> new LlmImageAttachment(image.mimeType(), image.content()))
                .toList();
    }

    private List<AiCanvasImageResolver.LoadedImage> loadReferenceImages(
            CurrentUser currentUser, JsonNode references) {
        if (references == null || references.isNull()) {
            return List.of();
        }
        if (!references.isArray()) {
            throw new InvalidRequestException("referenceImages 必须是 JSON 数组");
        }
        List<AiCanvasImageResolver.LoadedImage> images = new ArrayList<>();
        for (JsonNode item : references) {
            if (images.size() >= MAX_MEDIA_IMAGES) {
                break;
            }
            String url = referenceUrl(item);
            if (url == null || url.isBlank()) {
                continue;
            }
            images.add(imageResolver.load(url, currentUser));
        }
        return images;
    }

    private ScriptsResponse parseScripts(String content, int requestedCount) {
        JsonNode root = jsonCodec.parseModelJson(content);
        JsonNode scriptNodes = root.isArray() ? root : root.path("scripts");
        if (!scriptNodes.isArray() || scriptNodes.isEmpty()) {
            throw new InvalidRequestException("模型没有返回分镜脚本，请重试");
        }
        List<ScriptResponse> scripts = new ArrayList<>();
        int index = 0;
        for (JsonNode item : scriptNodes) {
            if (scripts.size() >= requestedCount) {
                break;
            }
            scripts.add(new ScriptResponse(
                    item.path("sceneNumber").asInt(index + 1),
                    item.path("description").asText(""),
                    item.path("cameraAngle").asText(""),
                    item.path("cameraMovement").asText(""),
                    item.path("lighting").asText(""),
                    item.path("mood").asText(""),
                    item.path("prompt").asText("")));
            index++;
        }
        if (scripts.size() != requestedCount) {
            throw new InvalidRequestException(
                    "Model returned %d scripts but %d were requested"
                            .formatted(scripts.size(), requestedCount));
        }
        JsonNode characterDNA = root.path("characterDNA");
        if (characterDNA.isMissingNode() || characterDNA.isNull()) {
            characterDNA = objectMapper.createObjectNode();
        }
        return new ScriptsResponse(
                scripts,
                root.path("styleAnchor").asText(""),
                characterDNA);
    }

    private static String referenceUrl(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isTextual()) {
            return item.asText();
        }
        for (String field : List.of("url", "imageUrl", "dataUrl")) {
            String value = item.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String characterText(List<CharacterDescription> characters) {
        if (characters == null || characters.isEmpty()) {
            return "未提供";
        }
        return characters.stream()
                .map(character -> "%s：%s".formatted(
                        valueOrEmpty(character.name()),
                        valueOrEmpty(character.description())))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String topicOf(String message) {
        String firstLine = message.strip().lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(NEW_CHAT_TITLE)
                .replaceAll("\\s+", " ")
                .strip();
        return firstLine.length() <= 30 ? firstLine : firstLine.substring(0, 30);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
