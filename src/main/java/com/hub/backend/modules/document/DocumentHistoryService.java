package com.hub.backend.modules.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.document.dto.DocumentActionRequest;
import com.hub.backend.modules.document.dto.DocumentActionResult;
import com.hub.backend.modules.document.dto.DocumentHistoryDetail;
import com.hub.backend.modules.document.dto.DocumentHistorySummary;
import com.hub.backend.modules.document.entity.DocumentEntity;
import com.hub.backend.modules.document.entity.DocumentHistoryEntity;
import com.hub.backend.modules.document.repository.DocumentHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records what each tool click produced, so the page can list and re-open past results.
 *
 * <p>Two rules shape this class:</p>
 * <ul>
 *   <li><b>Recording never breaks the action.</b> Every write is wrapped: a history failure is
 *       logged and swallowed, because losing the audit row is not a reason to fail a conversion the
 *       user already paid for.</li>
 *   <li><b>The body is stored as the user saw it</b> — text results verbatim, file results as the
 *       generated attachment id. Nothing is re-computed on read, so a re-opened result cannot
 *       silently differ from the original (models change, fonts change).</li>
 * </ul>
 */
@Service
public class DocumentHistoryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentHistoryService.class);

    /** Newest N entries per account are kept; the rest are trimmed on write. */
    private static final int MAX_PER_USER = 300;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OPTIONS_CHARS = 1000;
    private static final int MAX_ERROR_CHARS = 500;

    private final DocumentHistoryRepository repository;
    private final ObjectMapper objectMapper;

    public DocumentHistoryService(DocumentHistoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Stores a completed tool run. Called after the result has been produced. */
    public void recordSuccess(CurrentUser user, DocumentEntity document, DocumentActionRequest request,
                              DocumentActionResult result, long durationMs) {
        DocumentHistoryEntity row = baseRow(user, document, request, durationMs);
        row.setStatus(DocumentHistoryEntity.STATUS_SUCCESS);
        row.setResultText(result.text());
        row.setFileId(result.fileId());
        row.setFileName(trimTo(result.fileName(), 255));
        row.setFileFormat(trimTo(result.fileFormat(), 16));
        row.setTruncated(result.truncated());
        saveQuietly(row);
    }

    /** Stores a failed tool run: the page showed 「处理没有完成」, so the reason should be queryable. */
    public void recordFailure(CurrentUser user, DocumentEntity document, DocumentActionRequest request,
                              String message, long durationMs) {
        DocumentHistoryEntity row = baseRow(user, document, request, durationMs);
        row.setStatus(DocumentHistoryEntity.STATUS_FAILED);
        row.setErrorMessage(trimTo(message == null || message.isBlank()
                ? "处理失败" : message.strip(), MAX_ERROR_CHARS));
        saveQuietly(row);
    }

    /**
     * @param documentId optional; {@code null} or {@code <= 0} means every document
     * @param action     optional tool key
     * @param keyword    optional, matches document name or tool label
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentHistorySummary> search(CurrentUser user, Long documentId, String action,
                                                       String keyword, Integer page, Integer size) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        // LIKE 通配符从关键词里去掉：搜「50%」不应该匹配到所有记录。
        String cleaned = keyword == null ? "" : keyword.strip().replace("%", "").replace("_", "");
        String like = cleaned.isEmpty() ? "" : "%" + cleaned.toLowerCase(Locale.ROOT) + "%";

        Page<DocumentHistorySummary> result = repository.search(
                user.userId(),
                documentId == null || documentId <= 0 ? 0L : documentId,
                action == null ? "" : action.strip(),
                cleaned,
                like,
                PageRequest.of(pageIndex, pageSize));
        return PageResponse.of(result.getContent(), result.getTotalElements(), pageIndex, pageSize);
    }

    @Transactional(readOnly = true)
    public DocumentHistoryDetail detail(CurrentUser user, Long historyId) {
        DocumentHistoryEntity row = repository.findByIdAndUserId(historyId, user.userId())
                .orElseThrow(() -> new NotFoundException("这条处理历史不存在，可能已经被删除"));
        return DocumentHistoryDetail.of(row);
    }

    @Transactional
    public void delete(CurrentUser user, Long historyId) {
        DocumentHistoryEntity row = repository.findByIdAndUserId(historyId, user.userId())
                .orElseThrow(() -> new NotFoundException("这条处理历史不存在，可能已经被删除"));
        repository.delete(row);
    }

    @Transactional
    public int clear(CurrentUser user) {
        return repository.deleteAllForUser(user.userId());
    }

    private DocumentHistoryEntity baseRow(CurrentUser user, DocumentEntity document,
                                          DocumentActionRequest request, long durationMs) {
        DocumentHistoryEntity row = new DocumentHistoryEntity();
        row.setUserId(user.userId());
        row.setSiteId(document.getSiteId() != null ? document.getSiteId() : user.siteId());
        row.setDocumentId(document.getId());
        row.setDocumentName(trimTo(document.getOriginalName(), 512));
        row.setDocumentFormat(trimTo(document.getFormat(), 16));
        row.setAction(trimTo(request == null ? "" : request.action(), 48));
        row.setLabel(trimTo(DocumentActionService.labelOf(row.getAction()), 64));
        row.setOptionsJson(optionsJson(request));
        row.setDurationMs(durationMs < 0 ? null : (int) Math.min(durationMs, Integer.MAX_VALUE));
        return row;
    }

    private String optionsJson(DocumentActionRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, String> options = request.options();
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(options);
            return json.length() > MAX_OPTIONS_CHARS ? json.substring(0, MAX_OPTIONS_CHARS) : json;
        } catch (JsonProcessingException exception) {
            log.debug("处理历史的参数序列化失败，跳过：{}", exception.getMessage());
            return null;
        }
    }

    /** Save + retention trim. Swallows every failure — see the class comment. */
    private void saveQuietly(DocumentHistoryEntity row) {
        try {
            repository.save(row);
            trim(row.getUserId());
        } catch (RuntimeException exception) {
            log.warn("写入处理历史失败（不影响本次结果）：{}", exception.getMessage());
        }
    }

    private void trim(Long userId) {
        List<Long> ids = repository.findIdsForUser(userId, PageRequest.of(0, MAX_PER_USER + 1));
        if (ids.size() > MAX_PER_USER) {
            repository.deleteByUserIdAndIdNotIn(userId, ids.subList(0, MAX_PER_USER));
        }
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }
}
