package com.hub.backend.modules.admin.provider;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import com.hub.backend.modules.model.NewApiCatalogSyncService;
import com.hub.backend.modules.model.provider.LlmProviderEntity;
import com.hub.backend.modules.model.provider.LlmProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only provider catalogue backed by New API.
 *
 * <p>Provider credentials are deliberately not exposed here. Each end user's AI traffic is
 * authenticated with that user's relay token and New API chooses the channel internally.</p>
 */
@Service
@Transactional(readOnly = true)
public class LlmProviderAdminService {

    private final LlmProviderRepository repository;
    private final NewApiCatalogSyncService catalogSync;

    public LlmProviderAdminService(LlmProviderRepository repository,
                                   NewApiCatalogSyncService catalogSync) {
        this.repository = repository;
        this.catalogSync = catalogSync;
    }

    public List<LlmProviderResponse> list(String protocol, String status, String category) {
        String normalizedProtocol = normalize(protocol);
        String normalizedStatus = normalize(status);
        String normalizedCategory = normalize(category);
        return repository.findAll().stream()
                .filter(provider -> LlmProviderEntity.SOURCE_NEW_API.equals(provider.getSource()))
                .filter(provider -> normalizedProtocol == null
                        || normalizedProtocol.equals(normalize(provider.getProtocol())))
                .filter(provider -> normalizedStatus == null
                        || normalizedStatus.equals(normalize(provider.getStatus())))
                .filter(provider -> normalizedCategory == null
                        || normalizedCategory.equals(normalize(provider.getCategory())))
                .sorted(Comparator.comparingInt(LlmProviderEntity::getSortOrder)
                        .thenComparing(LlmProviderEntity::getId))
                .map(LlmProviderResponse::of)
                .toList();
    }

    public LlmProviderResponse get(Long id) {
        return LlmProviderResponse.of(require(id));
    }

    @Transactional
    public NewApiCatalogSyncResponse sync() {
        return catalogSync.sync();
    }

    /**
     * Sets the origin a finished video is downloaded from, one field per call.
     *
     * <p>Writes one column so a concurrent edit of the synced catalogue cannot be overwritten by a
     * read-modify-write of the whole row. Blank clears the override and restores the relay path.</p>
     *
     * @throws com.hub.backend.common.exception.InvalidRequestException when the value is not an
     *         absolute HTTP(S) origin — a relative value would silently resolve against the relay
     *         again, which is the failure this field exists to fix
     */
    @Transactional
    public LlmProviderResponse updateVideoDownloadBaseUrl(Long id, String value) {
        // Any source, not just the synced New API catalogue: a hand-registered supplier can need a
        // download origin too, and that is exactly the row an operator maintains by hand.
        LlmProviderEntity provider = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Provider %d was not found".formatted(id)));
        provider.setVideoDownloadBaseUrl(validateOrigin(value));
        return LlmProviderResponse.of(repository.save(provider));
    }

    /**
     * Sets the key used to fetch a finished video from {@link
     * LlmProviderEntity#getVideoDownloadBaseUrl()}, one field per call.
     *
     * <p>This exists so a supplier origin and its credential are both ordinary row data: without
     * it the only way to give the download hop its own key was an environment variable, which
     * meant switching suppliers required a redeploy. The column is not touched by the catalogue
     * sync, so the value survives refreshes — unlike {@code api_key} on the same row.</p>
     *
     * <p>Blank clears it and falls back to the row key, then to
     * {@code app.llm.video-download-api-key}, so clearing is always reversible without a restart.
     * The value is never echoed back: the response only says whether one is set.</p>
     */
    @Transactional
    public LlmProviderResponse updateVideoDownloadApiKey(Long id, String value) {
        // Any source: a hand-registered supplier can need its own download credential too.
        LlmProviderEntity provider = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Provider %d was not found".formatted(id)));
        provider.setVideoDownloadApiKey(validateSecret(value));
        return LlmProviderResponse.of(repository.save(provider));
    }

    /**
     * @throws InvalidRequestException when the value carries whitespace, which is what a
     *         copy-pasted key picks up and is then rejected by the supplier as an invalid token
     */
    private static String validateSecret(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidRequestException("videoDownloadApiKey must not contain whitespace");
        }
        if (trimmed.regionMatches(true, 0, "bearer ", 0, 7)) {
            // The header is built by the downloader; a key that already carries the scheme would
            // be sent as "Bearer Bearer sk-...".
            throw new InvalidRequestException(
                    "videoDownloadApiKey must be the raw key, without the 'Bearer ' prefix");
        }
        return trimmed;
    }

    private static String validateOrigin(String value) {        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new InvalidRequestException(
                    "videoDownloadBaseUrl must be an absolute http(s) origin, e.g. https://api.example.com");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private LlmProviderEntity require(Long id) {
        return repository.findById(id)
                .filter(provider -> LlmProviderEntity.SOURCE_NEW_API.equals(provider.getSource()))
                .orElseThrow(() -> new NotFoundException(
                        "Provider %d was not found".formatted(id)));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }
}
