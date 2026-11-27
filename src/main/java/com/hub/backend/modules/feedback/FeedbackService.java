package com.hub.backend.modules.feedback;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.feedback.dto.AdminFeedbackListResponse;
import com.hub.backend.modules.feedback.dto.AdminFeedbackResponse;
import com.hub.backend.modules.feedback.dto.FeedbackRequest;
import com.hub.backend.modules.feedback.dto.FeedbackResponse;
import com.hub.backend.modules.feedback.entity.UserFeedbackEntity;
import com.hub.backend.modules.feedback.repository.UserFeedbackRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stores messages from the public feedback page and serves the admin list.
 *
 * <p>The page is open to guests, so {@code user} may be null; when it is present the row keeps
 * the account and site ids for follow-up. Content length is re-checked here on top of the DTO
 * annotations because {@code @NotBlank} alone would happily store a single character.
 */
@Service
@Transactional(readOnly = true)
public class FeedbackService {

    public static final int MIN_CONTENT_LENGTH = 10;
    public static final int MAX_CONTENT_LENGTH = 2000;

    static final String STATUS_NEW = UserFeedbackEntity.STATUS_NEW;
    static final String STATUS_HANDLED = UserFeedbackEntity.STATUS_HANDLED;

    private static final Set<String> CATEGORIES = Set.of(
            "FEATURE", "BUG", "BILLING", "OTHER");
    private static final Set<String> STATUSES = Set.of(STATUS_NEW, STATUS_HANDLED);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final UserFeedbackRepository feedback;
    private final UserAccountRepository accounts;

    public FeedbackService(UserFeedbackRepository feedback, UserAccountRepository accounts) {
        this.feedback = feedback;
        this.accounts = accounts;
    }

    @Transactional
    public FeedbackResponse submit(CurrentUser user, FeedbackRequest request) {
        String content = request.content() == null ? "" : request.content().strip();
        if (content.length() < MIN_CONTENT_LENGTH) {
            throw new InvalidRequestException("反馈内容至少需要 " + MIN_CONTENT_LENGTH + " 个字");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new InvalidRequestException("反馈内容不能超过 " + MAX_CONTENT_LENGTH + " 个字");
        }

        UserFeedbackEntity entity = new UserFeedbackEntity();
        entity.setUserId(user == null ? null : user.userId());
        entity.setSiteId(user == null ? null : user.siteId());
        entity.setCategory(normalizeCategory(request.category()));
        entity.setContent(content);
        entity.setContact(trimToNull(request.contact()));
        entity.setPageUrl(trimToNull(request.pageUrl()));

        UserFeedbackEntity saved = feedback.saveAndFlush(entity);
        return new FeedbackResponse(saved.getId(), saved.getCreatedAt());
    }

    public AdminFeedbackListResponse adminList(String status,
                                               String category,
                                               String keyword,
                                               int page,
                                               int pageSize) {
        return adminList(status, category, null, keyword, page, pageSize);
    }

    public AdminFeedbackListResponse adminList(String status,
                                               String category,
                                               String source,
                                               String keyword,
                                               int page,
                                               int pageSize) {
        String statusFilter = optionalStatus(status);
        String categoryFilter = optionalCategory(category);
        String sourceFilter = trimToNull(source);
        String keywordFilter = trimToNull(keyword);

        Page<UserFeedbackEntity> result = feedback.searchAdmin(
                statusFilter, categoryFilter, sourceFilter, keywordFilter, pageRequest(page, pageSize));
        Map<Long, String> usernames = usernamesByIds(result.getContent().stream()
                .map(UserFeedbackEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));

        List<AdminFeedbackResponse> items = result.getContent().stream()
                // 游客提交时 userId 为 null；Map.of() 不接受 null key，必须先挡一下。
                .map(item -> AdminFeedbackResponse.from(item, item.getUserId() == null
                        ? null
                        : usernames.get(item.getUserId())))
                .toList();
        return new AdminFeedbackListResponse(
                items, result.getTotalElements(), feedback.countByStatus(STATUS_NEW));
    }

    @Transactional
    public AdminFeedbackResponse updateStatus(Long id, String status) {
        UserFeedbackEntity entity = feedback.findById(id)
                .orElseThrow(() -> new NotFoundException("反馈不存在"));
        entity.setStatus(normalizeStatus(status));
        UserFeedbackEntity saved = feedback.saveAndFlush(entity);
        return AdminFeedbackResponse.from(saved, usernameOf(saved.getUserId()));
    }

    @Transactional
    public void delete(Long id) {
        if (!feedback.existsById(id)) {
            throw new NotFoundException("反馈不存在");
        }
        feedback.deleteById(id);
    }

    private Map<Long, String> usernamesByIds(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return accounts.findAllById(ids).stream()
                .collect(Collectors.toMap(UserAccountEntity::getId,
                        account -> account.getUsername() == null ? "" : account.getUsername(),
                        (left, right) -> left));
    }

    private String usernameOf(Long userId) {
        if (userId == null) return "";
        return usernamesByIds(Set.of(userId)).getOrDefault(userId, "");
    }

    private static Pageable pageRequest(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return PageRequest.of(safePage - 1, safePageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    /** Unknown values are rejected instead of stored, otherwise the column stops being filterable. */
    private static String normalizeCategory(String raw) {
        String category = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new InvalidRequestException("反馈类型不正确");
        }
        return category;
    }

    /** Same rule as the category: only the two states the UI can produce are accepted. */
    private static String normalizeStatus(String raw) {
        String status = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new InvalidRequestException("反馈状态不正确");
        }
        return status;
    }

    /** A filter value that is absent or blank means "no filter", not "match nothing". */
    private static String optionalStatus(String raw) {
        String value = trimToNull(raw);
        return value == null ? null : normalizeStatus(value);
    }

    private static String optionalCategory(String raw) {
        String value = trimToNull(raw);
        return value == null ? null : normalizeCategory(value);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
