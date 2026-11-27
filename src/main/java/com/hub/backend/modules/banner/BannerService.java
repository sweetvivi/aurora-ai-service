package com.hub.backend.modules.banner;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import com.hub.backend.modules.banner.dto.BannerRequest;
import com.hub.backend.modules.banner.dto.BannerResponse;
import com.hub.backend.modules.banner.dto.BannerStatusRequest;
import com.hub.backend.modules.banner.entity.SiteBannerEntity;
import com.hub.backend.modules.banner.repository.SiteBannerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Banner lifecycle and the public selection rules.
 */
@Service
@Transactional(readOnly = true)
public class BannerService {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_CONTENT_LENGTH = 240;
    private static final int MAX_HIGHLIGHT_LENGTH = 80;
    private static final int MAX_BUTTON_LENGTH = 20;
    private static final int MAX_URL_LENGTH = 1000;
    private static final int MAX_SORT_ORDER = 100000;

    private final SiteBannerRepository banners;
    private final AppSiteRepository sites;

    public BannerService(SiteBannerRepository banners, AppSiteRepository sites) {
        this.banners = banners;
        this.sites = sites;
    }

    public List<BannerResponse> list(String status, Long siteId, String keyword) {
        String statusFilter = normalizeStatusFilter(status);
        if (siteId != null && siteId < 0) {
            throw new InvalidRequestException("siteId must not be negative");
        }
        String keywordFilter = optionalText(keyword, Integer.MAX_VALUE, "keyword");
        String normalizedKeyword = keywordFilter == null ? null : keywordFilter.toLowerCase(Locale.ROOT);

        return banners.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(banner -> statusFilter == null || statusFilter.equals(banner.getStatus()))
                .filter(banner -> matchesSite(banner, siteId))
                .filter(banner -> normalizedKeyword == null || matchesKeyword(banner, normalizedKeyword))
                .map(BannerService::toResponse)
                .toList();
    }

    public BannerResponse detail(Long id) {
        return toResponse(requireBanner(id));
    }

    /**
     * Returns the highest priority active banner for the site, falling back to a global row.
     */
    public BannerResponse current(Long siteId) {
        return banners.findCurrentCandidates(
                        SiteBannerEntity.STATUS_ACTIVE, siteId, LocalDateTime.now())
                .stream()
                .findFirst()
                .map(BannerService::toResponse)
                .orElse(null);
    }

    @Transactional
    public BannerResponse create(BannerRequest request) {
        SiteBannerEntity banner = new SiteBannerEntity();
        apply(banner, request);
        return toResponse(banners.saveAndFlush(banner));
    }

    @Transactional
    public BannerResponse update(Long id, BannerRequest request) {
        SiteBannerEntity banner = requireBanner(id);
        apply(banner, request);
        return toResponse(banners.saveAndFlush(banner));
    }

    @Transactional
    public BannerResponse updateStatus(Long id, BannerStatusRequest request) {
        SiteBannerEntity banner = requireBanner(id);
        banner.setStatus(normalizeStatus(request.status(), false));
        return toResponse(banners.saveAndFlush(banner));
    }

    @Transactional
    public void delete(Long id) {
        SiteBannerEntity banner = requireBanner(id);
        banners.delete(banner);
        banners.flush();
    }

    private void apply(SiteBannerEntity banner, BannerRequest request) {
        String buttonText = optionalText(request.buttonText(), MAX_BUTTON_LENGTH, "buttonText");
        String linkUrl = normalizeLinkUrl(request.linkUrl());
        if ((buttonText == null) != (linkUrl == null)) {
            throw new InvalidRequestException(
                    "buttonText and linkUrl must either both be present or both be empty");
        }

        if (request.siteId() != null && !sites.existsById(request.siteId())) {
            throw new InvalidRequestException("siteId %d does not exist".formatted(request.siteId()));
        }
        if (request.startAt() != null && request.endAt() != null
                && !request.startAt().isBefore(request.endAt())) {
            throw new InvalidRequestException("endAt must be later than startAt");
        }

        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            throw new InvalidRequestException(
                    "sortOrder must be between 0 and %d".formatted(MAX_SORT_ORDER));
        }

        banner.setName(requiredText(request.name(), MAX_NAME_LENGTH, "name"));
        banner.setTitle(requiredText(request.title(), MAX_TITLE_LENGTH, "title"));
        banner.setContent(requiredText(request.content(), MAX_CONTENT_LENGTH, "content"));
        banner.setHighlightText(optionalText(request.highlightText(), MAX_HIGHLIGHT_LENGTH, "highlightText"));
        banner.setButtonText(buttonText);
        banner.setLinkUrl(linkUrl);
        banner.setImageUrl(normalizeImageUrl(request.imageUrl()));
        banner.setStatus(normalizeStatus(request.status(), true));
        banner.setSiteId(request.siteId());
        banner.setStartAt(request.startAt());
        banner.setEndAt(request.endAt());
        banner.setDismissible(request.dismissible() == null || request.dismissible());
        banner.setSortOrder(sortOrder);
    }

    private SiteBannerEntity requireBanner(Long id) {
        return banners.findById(id)
                .orElseThrow(() -> new NotFoundException("Banner %d was not found".formatted(id)));
    }

    private static boolean matchesSite(SiteBannerEntity banner, Long siteId) {
        if (siteId == null) {
            return true;
        }
        if (siteId == 0L) {
            return banner.getSiteId() == null;
        }
        return Objects.equals(banner.getSiteId(), siteId);
    }

    private static boolean matchesKeyword(SiteBannerEntity banner, String keyword) {
        return containsIgnoreCase(banner.getName(), keyword)
                || containsIgnoreCase(banner.getTitle(), keyword)
                || containsIgnoreCase(banner.getContent(), keyword);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String requiredText(String value, int maxLength, String field) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty()) {
            throw new InvalidRequestException("%s must not be blank".formatted(field));
        }
        if (normalized.length() > maxLength) {
            throw new InvalidRequestException(
                    "%s must be at most %d characters".formatted(field, maxLength));
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new InvalidRequestException(
                    "%s must be at most %d characters".formatted(field, maxLength));
        }
        return normalized;
    }

    private static String normalizeLinkUrl(String value) {
        String normalized = optionalText(value, MAX_URL_LENGTH, "linkUrl");
        if (normalized == null) {
            return null;
        }
        if (!normalized.startsWith("/")
                && !normalized.startsWith("#")
                && !startsWithHttpScheme(normalized)) {
            throw new InvalidRequestException(
                    "linkUrl must start with http://, https://, / or #");
        }
        return normalized;
    }

    private static String normalizeImageUrl(String value) {
        String normalized = optionalText(value, MAX_URL_LENGTH, "imageUrl");
        if (normalized == null) {
            return null;
        }
        if (!normalized.startsWith("/") && !startsWithHttpScheme(normalized)) {
            throw new InvalidRequestException("imageUrl must start with http://, https:// or /");
        }
        return normalized;
    }

    private static boolean startsWithHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, "http://".length())
                || value.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    private static String normalizeStatusFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeStatus(value, false);
    }

    private static String normalizeStatus(String value, boolean defaultActive) {
        if (value == null || value.isBlank()) {
            if (defaultActive) {
                return SiteBannerEntity.STATUS_ACTIVE;
            }
            throw new InvalidRequestException("status must not be blank");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!SiteBannerEntity.STATUS_ACTIVE.equals(normalized)
                && !SiteBannerEntity.STATUS_DISABLED.equals(normalized)) {
            throw new InvalidRequestException("status must be ACTIVE or DISABLED");
        }
        return normalized;
    }

    private static BannerResponse toResponse(SiteBannerEntity banner) {
        return new BannerResponse(
                banner.getId(),
                banner.getName(),
                banner.getTitle(),
                banner.getContent(),
                banner.getHighlightText(),
                banner.getButtonText(),
                banner.getLinkUrl(),
                banner.getImageUrl(),
                banner.getStatus(),
                banner.getSiteId(),
                banner.getStartAt(),
                banner.getEndAt(),
                banner.isDismissible(),
                banner.getSortOrder(),
                banner.getCreatedAt(),
                banner.getUpdatedAt());
    }
}
