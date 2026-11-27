package com.hub.backend.modules.commerce;

import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.commerce.dto.CommerceHistoryResponse;
import com.hub.backend.modules.commerce.entity.CommerceGenerationHistoryEntity;
import com.hub.backend.modules.commerce.repository.CommerceGenerationHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceGenerationHistoryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PROMPT_LENGTH = 4000;

    private final CommerceGenerationHistoryRepository repository;

    public CommerceGenerationHistoryService(CommerceGenerationHistoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<CommerceHistoryResponse> list(CurrentUser currentUser, Integer page, Integer size) {
        int resolvedPage = page == null || page < 0 ? 0 : page;
        int resolvedSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);
        Page<CommerceGenerationHistoryEntity> result = repository.findBySiteIdAndUserIdOrderByCreatedAtDescIdDesc(
                currentUser.siteId(), currentUser.userId(), pageable);
        return PageResponse.of(result.getContent().stream().map(CommerceHistoryResponse::from).toList(),
                result.getTotalElements(), resolvedPage, resolvedSize);
    }

    @Transactional
    public void record(CurrentUser currentUser, String toolKey, String toolLabel,
                       String prompt, String resultUrl) {
        if (resultUrl == null || resultUrl.isBlank()) return;
        String safePrompt = prompt == null ? "" : prompt.strip();
        if (safePrompt.length() > MAX_PROMPT_LENGTH) safePrompt = safePrompt.substring(0, MAX_PROMPT_LENGTH);
        repository.save(new CommerceGenerationHistoryEntity(
                currentUser.siteId(), currentUser.userId(), toolKey, toolLabel, safePrompt, resultUrl));
    }

    @Transactional
    public void delete(CurrentUser currentUser, Long id) {
        CommerceGenerationHistoryEntity row = repository
                .findByIdAndSiteIdAndUserId(id, currentUser.siteId(), currentUser.userId())
                .orElseThrow(() -> new NotFoundException("电商生成记录不存在：" + id));
        repository.delete(row);
    }

    @Transactional
    public void clear(CurrentUser currentUser) {
        repository.deleteBySiteIdAndUserId(currentUser.siteId(), currentUser.userId());
    }
}
