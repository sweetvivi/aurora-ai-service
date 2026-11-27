package com.hub.backend.modules.smartedit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.smartedit.dto.RecordCanvasUpdateRequest;
import com.hub.backend.modules.smartedit.dto.RecordCreateRequest;
import com.hub.backend.modules.smartedit.dto.RecordMessagesUpdateRequest;
import com.hub.backend.modules.smartedit.dto.RecordPageResponse;
import com.hub.backend.modules.smartedit.dto.RecordResponse;
import com.hub.backend.modules.smartedit.dto.RecordTitleUpdateRequest;
import com.hub.backend.modules.smartedit.entity.SmartEditRecordEntity;
import com.hub.backend.modules.smartedit.repository.SmartEditRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Smart-editing record lifecycle.
 *
 * <p>Canvas and messages are frontend-owned JSON documents. The service keeps them as JSON text so
 * the board can gain fields without a database migration, while every lookup remains scoped by the
 * current account.</p>
 */
@Service
public class SmartEditRecordService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_ROWS = 10;
    private static final int MAX_ROWS = 100;
    private static final String DEFAULT_TITLE = "未命名画板";
    private static final String DEFAULT_CANVAS = "{\"images\":[],\"scale\":1}";
    private static final String DEFAULT_MESSAGES = "[]";

    private final SmartEditRecordRepository repository;
    private final ObjectMapper objectMapper;

    public SmartEditRecordService(SmartEditRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RecordPageResponse list(CurrentUser currentUser, Integer page, Integer rows) {
        int resolvedPage = page == null || page < 1 ? DEFAULT_PAGE : page;
        int resolvedRows = rows == null || rows < 1 ? DEFAULT_ROWS : Math.min(rows, MAX_ROWS);

        Page<SmartEditRecordEntity> result = repository
                .findByUserIdAndStatusOrderByUpdatedAtDescIdDesc(
                        currentUser.userId(),
                        SmartEditRecordEntity.STATUS_ACTIVE,
                        PageRequest.of(resolvedPage - 1, resolvedRows));

        return new RecordPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public RecordResponse get(Long id, CurrentUser currentUser) {
        return toResponse(requireOwned(id, currentUser));
    }

    @Transactional
    public RecordResponse create(CurrentUser currentUser, RecordCreateRequest request) {
        JsonNode canvas = request.canvas() == null ? readDocument(DEFAULT_CANVAS) : request.canvas();
        JsonNode messages = request.messages() == null ? readDocument(DEFAULT_MESSAGES) : request.messages();
        requireObject(canvas, "canvas");
        requireArray(messages, "messages");

        String title = request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title().strip();

        SmartEditRecordEntity entity = new SmartEditRecordEntity(
                currentUser.userId(),
                title,
                writeDocument(canvas),
                writeDocument(messages),
                coverOf(canvas),
                SmartEditRecordEntity.STATUS_ACTIVE);
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void updateCanvas(Long id, CurrentUser currentUser, RecordCanvasUpdateRequest request) {
        requireObject(request.canvas(), "canvas");
        SmartEditRecordEntity entity = requireOwned(id, currentUser);
        entity.updateCanvas(writeDocument(request.canvas()), coverOf(request.canvas()));
    }

    @Transactional
    public void updateMessages(Long id, CurrentUser currentUser, RecordMessagesUpdateRequest request) {
        requireArray(request.messages(), "messages");
        SmartEditRecordEntity entity = requireOwned(id, currentUser);
        entity.updateMessages(writeDocument(request.messages()));
    }

    @Transactional
    public void updateTitle(Long id, CurrentUser currentUser, RecordTitleUpdateRequest request) {
        SmartEditRecordEntity entity = requireOwned(id, currentUser);
        entity.updateTitle(request.title().strip());
    }

    @Transactional
    public void delete(Long id, CurrentUser currentUser) {
        repository.delete(requireOwned(id, currentUser));
    }

    @Transactional(readOnly = true)
    public List<RecordResponse> demos() {
        return repository.findByStatusOrderByCreatedAtDescIdDesc(SmartEditRecordEntity.STATUS_DEMO)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private SmartEditRecordEntity requireOwned(Long id, CurrentUser currentUser) {
        return repository.findByIdAndUserIdAndStatus(
                        id, currentUser.userId(), SmartEditRecordEntity.STATUS_ACTIVE)
                .orElseThrow(() -> new NotFoundException("修图项目 %d 不存在".formatted(id)));
    }

    private RecordResponse toResponse(SmartEditRecordEntity entity) {
        return new RecordResponse(
                entity.getId(),
                entity.getTitle(),
                readDocument(entity.getCanvasJson()),
                readDocument(entity.getMessagesJson()),
                entity.getCover() == null ? "" : entity.getCover(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private JsonNode readDocument(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已保存的修图文档无法解析", exception);
        }
    }

    private String writeDocument(JsonNode document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new InvalidRequestException("修图文档格式不正确");
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new InvalidRequestException("%s 必须是一个 JSON 对象".formatted(field));
        }
    }

    private static void requireArray(JsonNode value, String field) {
        if (value == null || !value.isArray()) {
            throw new InvalidRequestException("%s 必须是一个 JSON 数组".formatted(field));
        }
    }

    private static String coverOf(JsonNode canvas) {
        JsonNode images = canvas.path("images");
        if (!images.isArray() || images.isEmpty()) {
            return "";
        }
        String url = images.get(0).path("url").asText("");
        return url == null ? "" : url;
    }
}
