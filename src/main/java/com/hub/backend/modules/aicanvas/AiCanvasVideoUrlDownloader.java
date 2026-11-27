package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.config.properties.AiCanvasProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Downloads public video URLs for FFmpeg without exposing the backend to common SSRF targets.
 */
@Component
public class AiCanvasVideoUrlDownloader {

    private static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int BUFFER_SIZE = 16 * 1024;

    private final AiCanvasProperties properties;
    private final VideoHttpTransport transport;
    private final AddressResolver addressResolver;
    private final Predicate<InetAddress> addressAllowed;

    @Autowired
    public AiCanvasVideoUrlDownloader(AiCanvasProperties properties) {
        this(
                properties,
                defaultHttpClient(),
                AiCanvasVideoUrlDownloader::resolveAll,
                AiCanvasVideoUrlDownloader::isPublicAddress);
    }

    AiCanvasVideoUrlDownloader(AiCanvasProperties properties,
                               HttpClient httpClient,
                               AddressResolver addressResolver) {
        this(
                properties,
                httpClient,
                addressResolver,
                AiCanvasVideoUrlDownloader::isPublicAddress);
    }

    AiCanvasVideoUrlDownloader(AiCanvasProperties properties,
                               HttpClient httpClient,
                               AddressResolver addressResolver,
                               Predicate<InetAddress> addressAllowed) {
        this(
                properties,
                request -> httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream()),
                addressResolver,
                addressAllowed);
    }

    AiCanvasVideoUrlDownloader(AiCanvasProperties properties,
                               VideoHttpTransport transport,
                               AddressResolver addressResolver,
                               Predicate<InetAddress> addressAllowed) {
        this.properties = properties;
        this.transport = transport;
        this.addressResolver = addressResolver;
        this.addressAllowed = addressAllowed;
    }

    public DownloadedVideo download(String rawUrl) {
        URI target = validateTarget(rawUrl);
        long maxBytes = properties.getMaxAssetSize().toBytes();

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpResponse<InputStream> response = send(target);
            int status = response.statusCode();

            if (isRedirect(status)) {
                closeQuietly(response.body());
                if (redirectCount == MAX_REDIRECTS) {
                    throw new InvalidRequestException(
                            "videoDataUrl URL exceeded the redirect limit");
                }
                String location = response.headers().firstValue("Location")
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> new InvalidRequestException(
                                "videoDataUrl URL redirect has no Location header"));
                target = validateTarget(target.resolve(location.strip()).toString());
                continue;
            }

            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new ServiceUnavailableException(
                        "Video download returned HTTP %d".formatted(status));
            }

            String mimeType = response.headers()
                    .firstValue("Content-Type")
                    .map(value -> value.split(";")[0].strip().toLowerCase(Locale.ROOT))
                    .filter(value -> !value.isEmpty())
                    .orElseThrow(() -> {
                        closeQuietly(response.body());
                        return new InvalidRequestException(
                                "videoDataUrl URL response has no Content-Type");
                    });
            String extension = extensionForMime(mimeType);

            response.headers().firstValueAsLong("Content-Length").ifPresent(length -> {
                if (length > maxBytes) {
                    closeQuietly(response.body());
                    throw tooLarge(maxBytes);
                }
            });

            byte[] content = readLimited(response.body(), maxBytes);
            if (content.length == 0) {
                throw new InvalidRequestException(
                        "videoDataUrl URL returned an empty video");
            }
            return new DownloadedVideo(content, extension, mimeType);
        }

        throw new InvalidRequestException("videoDataUrl URL could not be downloaded");
    }

    private URI validateTarget(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRequestException("videoDataUrl must not be blank");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("videoDataUrl URL is invalid");
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new InvalidRequestException(
                    "videoDataUrl URL must use http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidRequestException(
                    "videoDataUrl URL must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidRequestException("videoDataUrl URL has no host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        InetAddress[] addresses;
        try {
            addresses = addressResolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw new InvalidRequestException(
                    "videoDataUrl URL host could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new InvalidRequestException(
                    "videoDataUrl URL host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (!addressAllowed.test(address)) {
                throw new InvalidRequestException(
                        "videoDataUrl URL resolves to a private or reserved address");
            }
        }
        return uri;
    }

    private HttpResponse<InputStream> send(URI target) {
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(properties.getVideoDownloadTimeout())
                .header("Accept", "video/*")
                .GET()
                .build();
        try {
            return transport.send(request);
        } catch (IOException exception) {
            throw new ServiceUnavailableException(
                    "Video download failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Video download was interrupted");
        }
    }

    private static byte[] readLimited(InputStream input, long maxBytes) {
        try (input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(maxBytes, BUFFER_SIZE));
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw tooLarge(maxBytes);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ServiceUnavailableException(
                    "Video download failed while reading the response");
        }
    }

    private static String extensionForMime(String mimeType) {
        return switch (mimeType) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "video/x-msvideo" -> "avi";
            default -> throw new InvalidRequestException(
                    "videoDataUrl URL returned unsupported content type: " + mimeType);
        };
    }

    private static PayloadTooLargeException tooLarge(long maxBytes) {
        return new PayloadTooLargeException(
                "videoDataUrl URL exceeds the %d byte limit".formatted(maxBytes));
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

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Best-effort cleanup before returning an HTTP error.
        }
    }

    @FunctionalInterface
    interface VideoHttpTransport {
        HttpResponse<InputStream> send(HttpRequest request)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record DownloadedVideo(byte[] content, String extension, String mimeType) {
    }
}
