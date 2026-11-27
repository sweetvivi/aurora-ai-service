package com.hub.backend.modules.appconfig;

import com.hub.backend.common.config.properties.AppProperties;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.appconfig.entity.AppSiteEntity;
import com.hub.backend.modules.appconfig.repository.AppSiteRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves which white label site a request belongs to.
 *
 * <p>Order of precedence:</p>
 * <ol>
 *   <li>the {@code app-name} header, when the caller states a site explicitly;</li>
 *   <li>the request host, mapped through {@code app_sites.domain};</li>
 *   <li>the configured default site.</li>
 * </ol>
 *
 * <p>An explicitly requested but unknown site is rejected instead of silently falling back, so a
 * mis-configured client cannot be served another tenant's configuration.</p>
 */
@Component
@Transactional(readOnly = true)
public class ChannelResolver {

    private final AppSiteRepository repository;
    private final AppProperties appProperties;

    public ChannelResolver(AppSiteRepository repository, AppProperties appProperties) {
        this.repository = repository;
        this.appProperties = appProperties;
    }

    public AppSiteEntity resolve(HttpServletRequest request) {
        return resolve(request.getHeader(appProperties.getSiteHeader()), request.getServerName());
    }

    /**
     * @param requestedSiteKey value of the site header, may be {@code null}
     * @param host             request host without port, may be {@code null}
     */
    public AppSiteEntity resolve(String requestedSiteKey, String host) {
        if (requestedSiteKey != null && !requestedSiteKey.isBlank()) {
            String siteKey = requestedSiteKey.strip();
            return repository.findBySiteKey(siteKey)
                    .orElseThrow(() -> new NotFoundException("Site '%s' was not found".formatted(siteKey)));
        }

        if (appProperties.isDomainFallback() && host != null && !host.isBlank()) {
            Optional<AppSiteEntity> byDomain = repository.findFirstByDomainIgnoreCase(host.strip());
            if (byDomain.isPresent()) {
                return byDomain.get();
            }
        }

        String defaultKey = appProperties.getDefaultKey();
        return repository.findBySiteKey(defaultKey)
                .orElseThrow(() -> new NotFoundException("Default site '%s' was not found".formatted(defaultKey)));
    }
}
