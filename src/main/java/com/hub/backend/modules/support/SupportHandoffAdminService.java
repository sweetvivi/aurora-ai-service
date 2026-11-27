package com.hub.backend.modules.support;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.support.dto.SupportHandoffAdminItem;
import com.hub.backend.modules.support.dto.SupportHandoffAdminListResponse;
import com.hub.backend.modules.support.entity.SupportHandoffEntity;
import com.hub.backend.modules.support.repository.SupportHandoffRepository;
import com.hub.backend.modules.user.entity.UserAccountEntity;
import com.hub.backend.modules.user.repository.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SupportHandoffAdminService {

    private static final Set<String> STATUSES = Set.of(
            SupportHandoffEntity.STATUS_NEW,
            SupportHandoffEntity.STATUS_HANDLED);

    private final SupportHandoffRepository handoffs;
    private final UserAccountRepository accounts;

    public SupportHandoffAdminService(SupportHandoffRepository handoffs,
                                      UserAccountRepository accounts) {
        this.handoffs = handoffs;
        this.accounts = accounts;
    }

    public SupportHandoffAdminListResponse list(String status,
                                                String keyword,
                                                int page,
                                                int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = pageSize < 1 ? 20 : Math.min(pageSize, 100);
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        String statusFilter = optionalStatus(status);
        String raw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        String like = raw == null ? null : "%" + raw.toLowerCase(Locale.ROOT) + "%";

        Page<SupportHandoffEntity> result = handoffs.searchAdmin(statusFilter, like, raw, pageable);
        Map<Long, String> usernames = usernames(result.getContent().stream()
                .map(SupportHandoffEntity::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        List<SupportHandoffAdminItem> items = result.getContent().stream()
                .map(row -> toItem(row, row.getUserId() == null
                        ? ""
                        : usernames.getOrDefault(row.getUserId(), "")))
                .toList();
        return new SupportHandoffAdminListResponse(
                items,
                result.getTotalElements(),
                handoffs.countByStatus(SupportHandoffEntity.STATUS_NEW));
    }

    public SupportHandoffAdminItem detail(Long id) {
        SupportHandoffEntity entity = handoffs.findById(id)
                .orElseThrow(() -> new NotFoundException("转人工工单不存在"));
        return toItem(entity, usernameOf(entity.getUserId()));
    }

    @Transactional
    public SupportHandoffAdminItem updateStatus(Long id, String status) {
        SupportHandoffEntity entity = handoffs.findById(id)
                .orElseThrow(() -> new NotFoundException("转人工工单不存在"));
        String normalized = normalizeStatus(status);
        entity.setStatus(normalized);
        entity.setHandledAt(SupportHandoffEntity.STATUS_HANDLED.equals(normalized)
                ? LocalDateTime.now()
                : null);
        SupportHandoffEntity saved = handoffs.saveAndFlush(entity);
        return toItem(saved, usernameOf(saved.getUserId()));
    }

    @Transactional
    public void delete(Long id) {
        if (!handoffs.existsById(id)) {
            throw new NotFoundException("转人工工单不存在");
        }
        handoffs.deleteById(id);
    }

    private SupportHandoffAdminItem toItem(SupportHandoffEntity row, String username) {
        return new SupportHandoffAdminItem(
                row.getId(),
                row.getUserId(),
                username == null ? "" : username,
                row.getContent(),
                row.getContact() == null ? "" : row.getContact(),
                row.getPageUrl() == null ? "" : row.getPageUrl(),
                row.getAgentConversationId(),
                row.getTranscriptSnapshot() == null ? "" : row.getTranscriptSnapshot(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getHandledAt());
    }

    private Map<Long, String> usernames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accounts.findAllById(ids).stream()
                .collect(Collectors.toMap(
                        UserAccountEntity::getId,
                        account -> account.getUsername() == null ? "" : account.getUsername(),
                        (left, right) -> left));
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return "";
        }
        return usernames(Set.of(userId)).getOrDefault(userId, "");
    }

    private static String optionalStatus(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        return normalizeStatus(raw);
    }

    private static String normalizeStatus(String raw) {
        String status = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new InvalidRequestException("工单状态不正确");
        }
        return status;
    }
}
