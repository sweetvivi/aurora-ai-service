package com.hub.backend.modules.aicanvas;

import com.hub.backend.common.config.properties.AiCanvasProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCanvasVideoUrlDownloaderTest {

    private static final byte[] VIDEO_BYTES =
            "fake-mp4-video".getBytes(StandardCharsets.US_ASCII);

    private AiCanvasProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiCanvasProperties();
        properties.setMaxAssetSize(DataSize.ofMegabytes(200));
        properties.setVideoDownloadTimeout(Duration.ofSeconds(2));
    }

    @Test
    void rejectsUnsupportedSchemesCredentialsAndPrivateAddresses() {
        AiCanvasVideoUrlDownloader downloader = downloader(
                request -> {
                    throw new AssertionError("HTTP transport must not be called");
                },
                AiCanvasVideoUrlDownloaderTest::resolveAddresses);

        assertThrows(InvalidRequestException.class,
                () -> downloader.download("file:///tmp/input.mp4"));
        assertThrows(InvalidRequestException.class,
                () -> downloader.download("https://user:pass@example.com/input.mp4"));
        assertThrows(InvalidRequestException.class,
                () -> downloader.download("http://127.0.0.1/input.mp4"));
        assertThrows(InvalidRequestException.class,
                () -> downloader.download("http://[::1]/input.mp4"));
    }

    @Test
    void downloadsVideoAndFollowsValidatedRedirect() {
        Deque<HttpResponse<InputStream>> responses = new ArrayDeque<>();
        responses.add(response(302, Map.of(
                "Location", List.of("https://cdn.example.com/final.webm")), new byte[0]));
        responses.add(response(200, Map.of(
                "Content-Type", List.of("video/webm; charset=binary")), VIDEO_BYTES));

        AiCanvasVideoUrlDownloader downloader = downloader(
                request -> nextResponse(request, responses),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});

        AiCanvasVideoUrlDownloader.DownloadedVideo video =
                downloader.download("https://cdn.example.com/start");

        assertArrayEquals(VIDEO_BYTES, video.content());
        assertEquals("webm", video.extension());
        assertEquals("video/webm", video.mimeType());
    }

    @Test
    void rejectsRedirectsToPrivateAddresses() {
        Deque<HttpResponse<InputStream>> responses = new ArrayDeque<>();
        responses.add(response(302, Map.of(
                "Location", List.of("http://127.0.0.1/private.mp4")), new byte[0]));

        AiCanvasVideoUrlDownloader downloader = downloader(
                request -> nextResponse(request, responses),
                AiCanvasVideoUrlDownloaderTest::resolveAddresses);

        assertThrows(InvalidRequestException.class,
                () -> downloader.download("https://cdn.example.com/start"));
    }

    @Test
    void rejectsUnsupportedMimeTypesAndOversizedBodies() {
        AiCanvasVideoUrlDownloader unsupportedMime = downloader(
                request -> response(200, Map.of(
                        "Content-Type", List.of("application/octet-stream")), VIDEO_BYTES),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        assertThrows(InvalidRequestException.class,
                () -> unsupportedMime.download("https://cdn.example.com/input.mp4"));

        properties.setMaxAssetSize(DataSize.ofBytes(4));
        AiCanvasVideoUrlDownloader oversized = downloader(
                request -> response(200, Map.of(
                        "Content-Type", List.of("video/mp4"),
                        "Content-Length", List.of("5")), VIDEO_BYTES),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        assertThrows(PayloadTooLargeException.class,
                () -> oversized.download("https://cdn.example.com/input.mp4"));
    }

    @Test
    void rejectsOversizedStreamWhenContentLengthIsMissing() {
        properties.setMaxAssetSize(DataSize.ofBytes(4));
        AiCanvasVideoUrlDownloader downloader = downloader(
                request -> response(200, Map.of(
                        "Content-Type", List.of("video/mp4")), VIDEO_BYTES),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});

        assertThrows(PayloadTooLargeException.class,
                () -> downloader.download("https://cdn.example.com/input.mp4"));
    }

    @Test
    void reportsUpstreamHttpFailures() {
        AiCanvasVideoUrlDownloader downloader = downloader(
                request -> response(404, Map.of(), new byte[0]),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});

        assertThrows(ServiceUnavailableException.class,
                () -> downloader.download("https://cdn.example.com/missing.mp4"));
    }

    private AiCanvasVideoUrlDownloader downloader(
            AiCanvasVideoUrlDownloader.VideoHttpTransport transport,
            AiCanvasVideoUrlDownloader.AddressResolver resolver) {
        return new AiCanvasVideoUrlDownloader(
                properties,
                transport,
                resolver,
                AiCanvasVideoUrlDownloader::isPublicAddress);
    }

    private static InetAddress[] resolveAddresses(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    private static HttpResponse<InputStream> nextResponse(
            HttpRequest request,
            Deque<HttpResponse<InputStream>> responses) {
        HttpResponse<InputStream> response = responses.pollFirst();
        if (response == null) {
            throw new IllegalStateException("Unexpected request: " + request.uri());
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(
            int status,
            Map<String, List<String>> headers,
            byte[] body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        return response;
    }
}
