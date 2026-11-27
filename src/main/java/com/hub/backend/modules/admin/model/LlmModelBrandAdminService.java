package com.hub.backend.modules.admin.model;

import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.model.ModelBrandKeys;
import com.hub.backend.modules.model.entity.LlmModelBrandEntity;
import com.hub.backend.modules.model.repository.LlmModelBrandRepository;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Admin CRUD for the operational model-brand (vendor) layer.
 */
@Service
public class LlmModelBrandAdminService {

    private static final int MAX_SORT_ORDER = 1_000_000;

    private final LlmModelBrandRepository brands;
    private final LlmModelRepository models;

    public LlmModelBrandAdminService(LlmModelBrandRepository brands, LlmModelRepository models) {
        this.brands = brands;
        this.models = models;
    }

    @Transactional(readOnly = true)
    public List<AdminModelBrandResponse> list(String status, String keyword) {
        String statusFilter = normalizeStatusFilter(status);
        String needle = keyword == null || keyword.isBlank() ? null : keyword.strip().toLowerCase(Locale.ROOT);
        List<String> companies = models.findAllCompanies();

        return brands.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(brand -> statusFilter == null || statusFilter.equals(brand.getStatus()))
                .filter(brand -> needle == null || matchesKeyword(brand, needle))
                .map(brand -> AdminModelBrandResponse.of(brand, countLinked(brand, companies)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminModelBrandResponse detail(Long id) {
        LlmModelBrandEntity brand = requireBrand(id);
        return AdminModelBrandResponse.of(brand, countLinked(brand, models.findAllCompanies()));
    }

    @Transactional
    public AdminModelBrandResponse create(AdminModelBrandRequest request) {
        String brandKey = normalizeBrandKey(request.brandKey());
        if (brands.existsByBrandKey(brandKey)) {
            throw new ConflictException("brandKey '%s' already exists".formatted(brandKey));
        }
        LlmModelBrandEntity brand = new LlmModelBrandEntity(brandKey, "");
        apply(brand, request, brandKey);
        return AdminModelBrandResponse.of(brands.saveAndFlush(brand), 0);
    }

    @Transactional
    public AdminModelBrandResponse update(Long id, AdminModelBrandRequest request) {
        LlmModelBrandEntity brand = requireBrand(id);
        String brandKey = normalizeBrandKey(request.brandKey());
        if (brands.existsByBrandKeyAndIdNot(brandKey, id)) {
            throw new ConflictException("brandKey '%s' already exists".formatted(brandKey));
        }
        apply(brand, request, brandKey);
        return AdminModelBrandResponse.of(
                brands.saveAndFlush(brand),
                countLinked(brand, models.findAllCompanies()));
    }

    @Transactional
    public AdminModelBrandResponse updateStatus(Long id, AdminModelBrandStatusRequest request) {
        LlmModelBrandEntity brand = requireBrand(id);
        brand.setStatus(normalizeStatus(request.status(), false));
        return AdminModelBrandResponse.of(
                brands.saveAndFlush(brand),
                countLinked(brand, models.findAllCompanies()));
    }

    @Transactional
    public void delete(Long id) {
        LlmModelBrandEntity brand = requireBrand(id);
        brands.delete(brand);
        brands.flush();
    }

    private void apply(LlmModelBrandEntity brand, AdminModelBrandRequest request, String brandKey) {
        String displayName = requiredText(request.displayName(), 128, "displayName");
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            throw new InvalidRequestException(
                    "sortOrder must be between 0 and %d".formatted(MAX_SORT_ORDER));
        }

        brand.setBrandKey(brandKey);
        brand.setDisplayName(displayName);
        brand.setIconUrl(optionalText(request.iconUrl(), 1024, "iconUrl"));
        brand.setDescription(optionalText(request.description(), 512, "description"));
        brand.setSortOrder(sortOrder);
        brand.setStatus(normalizeStatus(request.status(), true));
    }

    private LlmModelBrandEntity requireBrand(Long id) {
        return brands.findById(id)
                .orElseThrow(() -> new NotFoundException("Model brand %d was not found".formatted(id)));
    }

    private static int countLinked(LlmModelBrandEntity brand, List<String> companies) {
        int count = 0;
        for (String company : companies) {
            if (matchesCompany(brand, company)) {
                count++;
            }
        }
        return count;
    }

    /** Same matching rule the public families catalogue uses. */
    static boolean matchesCompany(LlmModelBrandEntity brand, String company) {
        if (company == null || company.isBlank()) {
            return false;
        }
        String trimmed = company.strip();
        if (trimmed.equalsIgnoreCase(brand.getDisplayName())) {
            return true;
        }
        return brand.getBrandKey().equals(ModelBrandKeys.familyKeyOf(trimmed));
    }

    private static boolean matchesKeyword(LlmModelBrandEntity brand, String needle) {
        return contains(brand.getBrandKey(), needle)
                || contains(brand.getDisplayName(), needle)
                || contains(brand.getDescription(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalizeBrandKey(String brandKey) {
        String normalized = brandKey == null ? "" : brandKey.strip().toLowerCase(Locale.ROOT);
        if (!ModelBrandKeys.isValidBrandKey(normalized)) {
            throw new InvalidRequestException(
                    "brandKey must be lowercase kebab-case ascii and must not be 'other'");
        }
        return normalized;
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return normalizeStatus(status, false);
    }

    private static String normalizeStatus(String status, boolean allowBlankDefault) {
        if (status == null || status.isBlank()) {
            if (allowBlankDefault) {
                return LlmModelBrandEntity.STATUS_ACTIVE;
            }
            throw new InvalidRequestException("status must not be blank");
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!LlmModelBrandEntity.STATUS_ACTIVE.equals(normalized)
                && !LlmModelBrandEntity.STATUS_DISABLED.equals(normalized)) {
            throw new InvalidRequestException("status must be ACTIVE or DISABLED");
        }
        return normalized;
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
}
