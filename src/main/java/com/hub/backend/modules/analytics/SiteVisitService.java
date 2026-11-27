package com.hub.backend.modules.analytics;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.analytics.dto.SiteVisitResponse;
import com.hub.backend.modules.analytics.entity.SiteVisitEntity;
import com.hub.backend.modules.analytics.repository.SiteVisitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteVisitService {

    private static final int MAX_PATH_LENGTH = 512;

    private final SiteVisitRepository repository;

    public SiteVisitService(SiteVisitRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SiteVisitResponse record(Long siteId, String rawPath) {
        String path = normalizePath(rawPath);
        if (isAdminPath(path)) {
            return new SiteVisitResponse(false);
        }
        repository.save(new SiteVisitEntity(siteId, path));
        return new SiteVisitResponse(true);
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new InvalidRequestException("path is required");
        }
        String path = rawPath.strip();
        int queryIndex = path.indexOf('?');
        int fragmentIndex = path.indexOf('#');
        int end = path.length();
        if (queryIndex >= 0) {
            end = Math.min(end, queryIndex);
        }
        if (fragmentIndex >= 0) {
            end = Math.min(end, fragmentIndex);
        }
        path = path.substring(0, end);
        if (!path.startsWith("/")) {
            throw new InvalidRequestException("path must start with /");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new InvalidRequestException(
                    "path must contain at most " + MAX_PATH_LENGTH + " characters");
        }
        if (path.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidRequestException("path must not contain control characters");
        }
        return path;
    }

    private static boolean isAdminPath(String path) {
        return path.equals("/admin") || path.startsWith("/admin/");
    }
}
