package com.hub.backend.modules.auth;

/**
 * Client details recorded on a session, for the device list and for incident review.
 *
 * @param clientType application defined label such as {@code web} or {@code app}
 * @param userAgent  raw User-Agent header
 * @param ipAddress  client address as determined by {@link ClientContextResolver}, never {@code null}
 */
public record ClientContext(String clientType, String userAgent, String ipAddress) {
}
