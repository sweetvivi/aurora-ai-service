package com.hub.backend.modules.auth;

import com.hub.backend.common.config.properties.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link ClientContext} of a request.
 *
 * <p>The client address feeds the registration rate limit, so where it comes from matters:</p>
 *
 * <ul>
 *   <li>by default the socket peer address ({@code getRemoteAddr()}) is used. It cannot be spoofed
 *       by the caller;</li>
 *   <li>{@code X-Forwarded-For} is only consulted when
 *       {@code app.security.trust-forwarded-for} is enabled, which must be done exclusively when a
 *       proxy in front overwrites the header. Otherwise an attacker would simply rotate the header
 *       value and never hit the limit.</li>
 * </ul>
 */
@Component
public class ClientContextResolver {

    /** Length of an IPv6 address with a scope suffix, matching the {@code ip_address} column. */
    private static final int MAX_IP_LENGTH = 45;

    private final SecurityProperties securityProperties;

    public ClientContextResolver(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public ClientContext resolve(HttpServletRequest request, String clientType) {
        return new ClientContext(clientType, request.getHeader(HttpHeaders.USER_AGENT), clientIp(request));
    }

    /**
     * @return the address used for auditing and for rate limiting; never {@code null}
     */
    String clientIp(HttpServletRequest request) {
        if (securityProperties.isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].strip();
                if (!first.isEmpty()) {
                    return truncate(first);
                }
            }
        }
        return truncate(request.getRemoteAddr());
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() <= MAX_IP_LENGTH ? value : value.substring(0, MAX_IP_LENGTH);
    }
}
