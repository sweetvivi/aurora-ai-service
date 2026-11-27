package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates user-supplied remote URLs before the backend fetches them itself.
 *
 * <p>Only absolute {@code http}/{@code https} URLs without embedded credentials are
 * accepted, and every resolved address must be publicly routable. This keeps endpoints
 * such as the Canvas image proxy from being turned into an SSRF pivot into the local
 * network or link-local metadata services.
 */
@Component
public class AiCanvasPublicUrlGuard {

    private final AddressResolver addressResolver;

    public AiCanvasPublicUrlGuard() {
        this(AiCanvasPublicUrlGuard::resolveAll);
    }

    AiCanvasPublicUrlGuard(AddressResolver addressResolver) {
        this.addressResolver = addressResolver;
    }

    /**
     * @param rawUrl    the URL supplied by the caller
     * @param fieldName request field name, only used in logs / internal diagnostics
     * @return the parsed URI, safe to hand to an HTTP client
     */
    public URI validate(String rawUrl, String fieldName) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRequestException("链接地址不能为空");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("链接地址格式不正确");
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new InvalidRequestException("链接仅支持 http 或 https");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidRequestException("链接不能包含账号密码");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidRequestException("链接缺少主机名");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        InetAddress[] addresses;
        try {
            addresses = addressResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw new InvalidRequestException("无法解析链接主机名");
        }
        if (addresses == null || addresses.length == 0) {
            throw new InvalidRequestException("无法解析链接主机名");
        }
        for (InetAddress address : addresses) {
            if (!AiCanvasVideoUrlDownloader.isPublicAddress(address)) {
                throw new InvalidRequestException("不允许访问内网或保留地址");
            }
        }
        return uri;
    }

    private static InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
