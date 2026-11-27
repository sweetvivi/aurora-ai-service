package com.hub.backend.modules.admin.provider;

import com.hub.backend.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminGuard {

    private static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final AdminProperties properties;

    public AdminGuard(AdminProperties properties) {
        this.properties = properties;
    }

    public void require(HttpServletRequest httpRequest) {
        String configured = properties.getApiKey();
        if (configured == null || configured.isBlank()) {
            throw new UnauthorizedException("Admin API key is not configured");
        }

        String requested = httpRequest.getHeader(ADMIN_KEY_HEADER);
        if (requested == null || requested.isBlank()
                || !MessageDigest.isEqual(
                        requested.getBytes(StandardCharsets.UTF_8),
                        configured.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Admin API key is required");
        }
    }
}
