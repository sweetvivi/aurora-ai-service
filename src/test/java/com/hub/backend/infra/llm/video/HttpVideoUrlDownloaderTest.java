package com.hub.backend.infra.llm.video;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The downloader's job is to tell a video apart from whatever a gateway happens to answer.
 *
 * <p>A stale signed link, a wrong route or a login wall all answer {@code 200} with markup, and
 * storing that page as the clip is silent corruption: the task reports success and the user gets
 * an unplayable file. These tests pin the rejection of the markup types and, just as importantly,
 * the acceptance of a server that declares nothing.</p>
 */
class HttpVideoUrlDownloaderTest {

    private static final byte[] VIDEO_BYTES = {(byte) 0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'};

    private HttpServer server;
    private HttpVideoUrlDownloader downloader;
    private final AtomicReference<String> contentType = new AtomicReference<>("video/mp4");
    private final AtomicReference<byte[]> body = new AtomicReference<>(VIDEO_BYTES);

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video", this::respond);
        server.createContext("/page", this::respond);
        server.start();

        LlmProperties properties = new LlmProperties();
        properties.setVideoTimeout(Duration.ofSeconds(5));
        properties.setBaseUrl(url("/v1"));

        StorageProperties storage = new StorageProperties();
        storage.setMaxFileSize(DataSize.ofMegabytes(64));

        downloader = new HttpVideoUrlDownloader(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                properties,
                storage);
    }

    @AfterEach
    void stopStubServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        String declared = contentType.get();
        if (declared != null) {
            exchange.getResponseHeaders().add("Content-Type", declared);
        }
        byte[] payload = body.get();
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void downloadsAVideoAndReportsItsMimeType() {
        VideoUrlDownloader.DownloadedVideo video = downloader.download(url("/video"), "sk-test");
        try {
            assertEquals(VIDEO_BYTES.length, video.sizeBytes());
            assertEquals("video/mp4", video.mimeType());
        } finally {
            video.close();
        }
    }

    @Test
    void rejectsAnHtmlPageServedWith200() {
        contentType.set("text/html; charset=utf-8");
        body.set("<html><body>Sign in</body></html>".getBytes());

        LlmInvocationException thrown = assertThrows(LlmInvocationException.class,
                () -> downloader.download(url("/page"), "sk-test"));

        assertTrue(thrown.getMessage().contains("text/html"),
                "the message must name the type actually served: " + thrown.getMessage());
    }

    @Test
    void rejectsOtherDocumentShapes() {
        for (String type : new String[]{"application/json", "application/xml", "text/plain"}) {
            contentType.set(type);
            assertThrows(LlmInvocationException.class,
                    () -> downloader.download(url("/page"), "sk-test"), type);
        }
    }

    @Test
    void acceptsAnOpaqueByteStream() {
        // application/octet-stream is what a CDN answers when it has no better guess; the bytes
        // are the only evidence available, so the type alone must not reject the download.
        contentType.set("application/octet-stream");
        VideoUrlDownloader.DownloadedVideo video = downloader.download(url("/video"), "sk-test");
        try {
            assertEquals(VIDEO_BYTES.length, video.sizeBytes());
            assertEquals("application/octet-stream", video.mimeType());
        } finally {
            video.close();
        }
    }

    @Test
    void acceptsAResponseThatDeclaresNoContentType() {
        contentType.set(null);
        VideoUrlDownloader.DownloadedVideo video = downloader.download(url("/video"), "sk-test");
        try {
            assertEquals(VIDEO_BYTES.length, video.sizeBytes());
            assertNull(video.mimeType());
        } finally {
            video.close();
        }
    }

    @Test
    void rejectsAnEmptyBody() {
        body.set(new byte[0]);
        LlmInvocationException thrown = assertThrows(LlmInvocationException.class,
                () -> downloader.download(url("/video"), "sk-test"));
        assertTrue(thrown.getMessage().contains("empty"), thrown.getMessage());
    }

    @Test
    void sendsTheKeyOnlyAsABearerToken() {
        AtomicReference<String> authorization = new AtomicReference<>();
        server.removeContext("/video");
        server.createContext("/video", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange);
        });

        VideoUrlDownloader.DownloadedVideo video = downloader.download(url("/video"), "sk-x");
        try {
            assertEquals("Bearer sk-x", authorization.get());
        } finally {
            video.close();
        }
    }

    @Test
    void omitsAuthorizationWhenNoKeyIsGiven() {
        AtomicReference<String> authorization = new AtomicReference<>("unset");
        server.removeContext("/video");
        server.createContext("/video", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange);
        });

        VideoUrlDownloader.DownloadedVideo video = downloader.download(url("/video"), null);
        try {
            assertNull(authorization.get(), "a public link must not carry a key");
        } finally {
            video.close();
        }
    }

    @Test
    void validatesTheUrlBeforeTouchingTheNetwork() {
        // Rejecting the URL is cheaper than a request, and a private address is the case that
        // matters: the downloader must not be turned into a probe for internal services.
        assertThrows(LlmInvocationException.class,
                () -> downloader.download("file:///tmp/x.mp4", null));
        assertThrows(LlmInvocationException.class,
                () -> downloader.download("", null));
        assertThrows(LlmInvocationException.class,
                () -> downloader.download("http://169.254.169.254/latest/meta-data/", null));
    }

    /**
     * A supplier declared on an internal address is a destination the deployment vouched for, so
     * the declaration has to reach the SSRF check. Otherwise the origin and key would live in the
     * database while the gate that lets them work stayed in configuration.
     */
    @Test
    void acceptsAPrivateAddressOnlyWhenTheCallerDeclaresThatOrigin() {
        LlmInvocationException rejected = assertThrows(LlmInvocationException.class,
                () -> downloader.validate("http://10.1.2.3/v1/videos/x/content"));
        assertTrue(rejected.getMessage().contains("non-public"), rejected.getMessage());

        assertDoesNotThrow(() -> downloader.validate(
                "http://10.1.2.3/v1/videos/x/content", "http://10.1.2.3"));

        // Another host must not borrow the exemption: the voucher names one origin, not a network.
        assertThrows(LlmInvocationException.class, () -> downloader.validate(
                "http://10.9.9.9/v1/videos/x/content", "http://10.1.2.3"));
        // Nor may a null voucher loosen anything.
        assertThrows(LlmInvocationException.class, () -> downloader.validate(
                "http://10.1.2.3/v1/videos/x/content", null));
    }

    @Test
    void keepsRejectingNonHttpSchemesEvenWithADeclaredOrigin() {
        assertThrows(LlmInvocationException.class,
                () -> downloader.validate("file:///etc/passwd", "http://10.1.2.3"));
    }
}
