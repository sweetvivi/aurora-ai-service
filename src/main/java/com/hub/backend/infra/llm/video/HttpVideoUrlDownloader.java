package com.hub.backend.infra.llm.video;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * HTTP downloader for video links returned by gateways.
 *
 * <p>Three protections that the image downloader does not need, because a video URL is longer
 * lived and more likely to come from an untrusted relay:</p>
 * <ul>
 *   <li>the host may not resolve to a loopback, link-local, site-local or multicast address, so a
 *       crafted URL cannot make the server fetch its own internal services;</li>
 *   <li>the response is streamed into a temporary file instead of a byte array;</li>
 *   <li>the declared size and the written size are both checked against the storage limit.</li>
 * </ul>
 */
@Component
public class HttpVideoUrlDownloader implements VideoUrlDownloader {

    private final HttpClient httpClient;
    private final LlmProperties llmProperties;
    private final StorageProperties storageProperties;

    public HttpVideoUrlDownloader(HttpClient llmHttpClient,
                                  LlmProperties llmProperties,
                                  StorageProperties storageProperties) {
        this.httpClient = llmHttpClient;
        this.llmProperties = llmProperties;
        this.storageProperties = storageProperties;
    }

    @Override
    public DownloadedVideo download(String url, String apiKey, String trustedOrigin) {
        URI uri = validate(url, trustedOrigin);
        Path target = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(llmProperties.getVideoDownloadTimeout())
                    .header("Accept", "video/*")
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                // The relay serves /v1/videos/{id}/content behind the same bearer token that
                // created the job, so an unauthenticated download answers 401.
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = builder.build();

            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new LlmInvocationException(
                        "Generated video download returned HTTP %d".formatted(response.statusCode()));
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .map(value -> value.split(";")[0].strip().toLowerCase(Locale.ROOT))
                    .filter(value -> !value.isEmpty())
                    .orElse(null);
            // A signed link that expired or a wrong route answers with the gateway's HTML page,
            // and its 200 would otherwise be stored as if it were the video. Rejecting the
            // markup types is what keeps a 4 kB landing page out of the file store; a server
            // that declares nothing is still accepted, since the byte checks below cover it.
            if (contentType != null && isMarkup(contentType)) {
                closeQuietly(response.body());
                throw new LlmInvocationException(
                        "Generated video download answered with %s instead of a video"
                                .formatted(contentType));
            }

            long declared = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            long limit = storageProperties.getMaxFileSize().toBytes();
            if (declared > limit) {
                closeQuietly(response.body());
                throw new LlmInvocationException(
                        "Generated video is larger than the %d byte limit".formatted(limit));
            }

            target = Files.createTempFile("video-result-", ".part");
            long written;
            try (InputStream body = response.body()) {
                written = Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (written == 0) {
                throw new LlmInvocationException("Generated video download returned an empty body");
            }
            if (written > limit) {
                throw new LlmInvocationException(
                        "Generated video is larger than the %d byte limit".formatted(limit));
            }

            String mimeType = response.headers()
                    .firstValue("Content-Type")
                    .map(value -> value.split(";")[0].strip())
                    .filter(value -> !value.isEmpty())
                    .orElse(null);
            return new DownloadedVideo(target, written, mimeType);
        } catch (IOException exception) {
            deleteQuietly(target);
            throw new LlmInvocationException(
                    "Generated video download failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(target);
            throw new LlmInvocationException("Generated video download was interrupted", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
    }

    /**
     * @throws LlmInvocationException when the URL is missing, not http(s), has no host, or points
     *         at a private / reserved address that is not the configured New API / result origin
     */
    URI validate(String url) {
        return validate(url, null);
    }

    /**
     * @param trustedOrigin an origin the caller declared for this download, {@code null} when there
     *                      is none; its host is exempt from the private-address rejection
     * @throws LlmInvocationException when the URL is missing, not http(s), has no host, or points
     *         at a private / reserved address that no configured origin vouches for
     */
    URI validate(String url, String trustedOrigin) {
        if (url == null || url.isBlank()) {
            throw new LlmInvocationException("Generated video URL is empty");
        }

        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException exception) {
            throw new LlmInvocationException("Generated video URL is invalid", exception);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new LlmInvocationException(
                    "Generated video URL must use http or https, got: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LlmInvocationException("Generated video URL has no host");
        }
        if (!isTrustedRelayHost(host) && !hostMatchesConfiguredOrigin(host, trustedOrigin)) {
            rejectPrivateAddress(host);
        }
        return uri;
    }

    /**
     * Local New API answers with {@code http://127.0.0.1:3000/v1/videos/.../content}. That host is
     * loopback, but it is our own relay — the same place chat already talks to — so SSRF rejection
     * would make every successful job fail at download.
     */
    private boolean isTrustedRelayHost(String host) {
        return hostMatchesConfiguredOrigin(host, llmProperties.resolvedBaseUrl())
                || hostMatchesConfiguredOrigin(host, llmProperties.getVideoResultBaseUrl());
    }

    private static boolean hostMatchesConfiguredOrigin(String host, String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            String configuredHost = URI.create(origin.strip()).getHost();
            return configuredHost != null && configuredHost.equalsIgnoreCase(host);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void rejectPrivateAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new LlmInvocationException("Generated video host cannot be resolved: " + host,
                    exception);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new LlmInvocationException(
                        "Generated video URL points at a non-public address: %s"
                                .formatted(address.getHostAddress()));
            }
        }
    }

    /**
     * @return whether the media type is a document rather than a video or an opaque byte stream.
     *         A video is served as {@code video/*} or {@code application/octet-stream}; HTML, JSON
     *         and XML are the shapes a gateway or CDN answers with when the link is wrong, expired
     *         or intercepted by a login page.
     */
    private static boolean isMarkup(String contentType) {
        return contentType.startsWith("text/")
                || contentType.equals("application/json")
                || contentType.equals("application/xml")
                || contentType.equals("application/xhtml+xml")
                || contentType.equals("application/problem+json");
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Nothing useful to do with a failed close on a rejected response.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The temp directory is reclaimed by the OS.
        }
    }

    /** @return lower-cased extension hint from the URL path, or {@code null} */
    public static String extensionOf(String url) {
        if (url == null) {
            return null;
        }
        String path = url.split("[?#]")[0];
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        String candidate = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return candidate.matches("[a-z0-9]{1,5}") ? candidate : null;
    }
}
