package com.hub.backend.infra.llm;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Predicate;

/**
 * Plain HTTP downloader for image links returned by OpenAI-compatible gateways.
 */
@Component
public class HttpImageUrlDownloader implements ImageUrlDownloader {

    private static final int MAX_REDIRECTS = 3;

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final AddressResolver addressResolver;
    private final Predicate<InetAddress> addressAllowed;

    @Autowired
    public HttpImageUrlDownloader(
            @Qualifier("imageDownloadHttpClient") HttpClient httpClient,
            LlmProperties properties) {
        this(httpClient, properties,
                HttpImageUrlDownloader::resolveAll,
                HttpImageUrlDownloader::isPublicAddress);
    }

    HttpImageUrlDownloader(HttpClient httpClient,
                           LlmProperties properties,
                           AddressResolver addressResolver,
                           Predicate<InetAddress> addressAllowed) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.addressResolver = addressResolver;
        this.addressAllowed = addressAllowed;
    }

    @Override
    public DownloadedImage download(String url) {
        URI target = validate(url);

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpResponse<byte[]> response = send(target);
            int status = response.statusCode();
            if (isRedirect(status)) {
                if (redirectCount == MAX_REDIRECTS) {
                    throw new LlmInvocationException(
                            "Generated image URL exceeded the redirect limit");
                }
                String location = response.headers().firstValue("Location")
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> new LlmInvocationException(
                                "Generated image URL redirect has no Location header"));
                target = validate(target.resolve(location.strip()).toString());
                continue;
            }

            if (status < 200 || status >= 300) {
                throw new LlmInvocationException(
                        "Generated image download returned HTTP %d".formatted(status));
            }

            byte[] content = response.body();
            if (content == null || content.length == 0) {
                throw new LlmInvocationException("Generated image download returned an empty body");
            }
            String mimeType = response.headers()
                    .firstValue("Content-Type")
                    .map(value -> value.split(";")[0].strip())
                    .filter(value -> !value.isEmpty())
                    .orElse(null);
            return new DownloadedImage(content, mimeType);
        }
        throw new LlmInvocationException("Generated image URL exceeded the redirect limit");
    }

    private HttpResponse<byte[]> send(URI target) {
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(properties.getImageTimeout())
                .header("Accept", "image/*")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new LlmInvocationException(
                    "Generated image download failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmInvocationException("Generated image download was interrupted", exception);
        }
    }

    private URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new LlmInvocationException("Generated image URL is empty");
        }

        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException exception) {
            throw new LlmInvocationException("Generated image URL is invalid", exception);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new LlmInvocationException(
                    "Generated image URL must use http or https, got: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new LlmInvocationException("Generated image URL must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LlmInvocationException("Generated image URL has no host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        InetAddress[] addresses;
        try {
            addresses = addressResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw new LlmInvocationException(
                    "Generated image URL host cannot be resolved: " + host, exception);
        }
        if (addresses == null || addresses.length == 0) {
            throw new LlmInvocationException(
                    "Generated image URL host cannot be resolved: " + host);
        }
        for (InetAddress address : addresses) {
            if (!addressAllowed.test(address)) {
                throw new LlmInvocationException(
                        "Generated image URL points at a non-public address: "
                                + address.getHostAddress());
            }
        }
        return uri;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && third == 0)
                    && !(first == 192 && second == 0 && third == 2)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113)
                    && first < 224;
        }

        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int uniqueLocal = first & 0xfe;
        return uniqueLocal != 0xfc
                && !(first == 0x20
                && Byte.toUnsignedInt(bytes[1]) == 0x01
                && Byte.toUnsignedInt(bytes[2]) == 0x0d
                && Byte.toUnsignedInt(bytes[3]) == 0xb8);
    }

    private static InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
