package com.hub.backend.modules.appconfig;

import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the tenant a request belongs to.
 *
 * <p>The public API does not trust a client supplied {@code Uid}; the site is always derived from
 * a server side key. Until {@code ChannelResolver} lands this service also acts as the fallback
 * for the local development site.</p>
 */
@Service
@Transactional(readOnly = true)
public class AppSiteService {

    private final AppSiteRepository repository;

    public AppSiteService(AppSiteRepository repository) {
        this.repository = repository;
    }

    public AppSiteEntity requireByKey(String siteKey) {
        return repository.findBySiteKey(siteKey)
                .orElseThrow(() -> new NotFoundException("Site '%s' was not found".formatted(siteKey)));
    }

    public Long requireSiteId(String siteKey) {
        return requireByKey(siteKey).getId();
    }
}
