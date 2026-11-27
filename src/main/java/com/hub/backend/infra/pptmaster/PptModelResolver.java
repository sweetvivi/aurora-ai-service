package com.hub.backend.infra.pptmaster;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.modules.model.entity.LlmModelEntity;
import com.hub.backend.modules.model.provider.ProviderCredentialResolver;
import com.hub.backend.modules.model.repository.LlmModelRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** Resolves a PPT model key to its catalogue route and server-side provider credentials. */
@Component
@Transactional(readOnly = true)
public class PptModelResolver {

    private final LlmModelRepository models;
    private final ProviderCredentialResolver providerCredentials;

    public PptModelResolver(LlmModelRepository models,
                            ProviderCredentialResolver providerCredentials) {
        this.models = models;
        this.providerCredentials = providerCredentials;
    }

    public ResolvedModel resolve(Long siteId,
                                 String requestedKey,
                                 String fallbackKey,
                                 String requiredType,
                                 ProviderCredentials fallbackCredentials) {
        String key = StringUtils.hasText(requestedKey)
                ? requestedKey.trim()
                : (StringUtils.hasText(fallbackKey) ? fallbackKey.trim() : null);
        if (!StringUtils.hasText(key)) {
            return new ResolvedModel(null, null, fallbackCredentials);
        }

        return models.findVisibleByModelKeyForResolution(siteId, key).stream()
                .findFirst()
                .map(model -> fromCatalog(model, key, requiredType, fallbackCredentials))
                .orElse(new ResolvedModel(key, key, fallbackCredentials));
    }

    private ResolvedModel fromCatalog(LlmModelEntity model,
                                      String key,
                                      String requiredType,
                                      ProviderCredentials fallbackCredentials) {
        if (!LlmModelEntity.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus())) {
            throw new NotFoundException("Model '%s' is not available".formatted(key));
        }
        if (requiredType != null && !requiredType.equalsIgnoreCase(model.getModelType())) {
            throw new InvalidRequestException(
                    "Model '%s' is registered as type %s and cannot be used for %s"
                            .formatted(key, model.getModelType(), purpose(requiredType)));
        }
        ProviderCredentials configured = providerCredentials.resolve(model.getProviderId());
        // A provider row without a usable key must not cause a configured relay deployment to lose
        // its working credential. Once the admin provider has a key, it is the complete authority.
        ProviderCredentials credentials = configured != null && configured.hasApiKey()
                ? configured
                : fallbackCredentials;
        return new ResolvedModel(key, model.resolvedRoute(), credentials);
    }

    private static String purpose(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case LlmModelEntity.TYPE_CHAT -> "chat";
            case LlmModelEntity.TYPE_IMAGE -> "image generation";
            default -> type.toLowerCase(Locale.ROOT);
        };
    }

    public record ResolvedModel(String requestKey, String upstreamModel,
                                ProviderCredentials credentials) {
    }
}
