package com.hub.backend.infra.llm;

import com.hub.backend.common.config.properties.LlmProperties;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.infra.llm.dto.DownloadedImage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpImageUrlDownloaderTest {

    private static final byte[] IMAGE_BYTES = {1, 2, 3, 4};

    private HttpServer server;
    private HttpImageUrlDownloader downloader;

    private volatile int responseStatus = 200;

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(responseStatus, IMAGE_BYTES.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(IMAGE_BYTES);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image.png");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-private", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location", "http://private.example/image.png");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        LlmProperties properties = new LlmProperties();
        properties.setImageTimeout(Duration.ofSeconds(5));
        downloader = new HttpImageUrlDownloader(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                properties,
                host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                address -> true);
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    @Test
    void downloadsTheImageAndKeepsTheResponseContentType() {
        DownloadedImage image = downloader.download(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png");

        assertArrayEquals(IMAGE_BYTES, image.content());
        assertEquals("image/png", image.mimeType());
    }

    @Test
    void followsAndRevalidatesRedirectsWithoutAutomaticRedirectFollowing() {
        DownloadedImage image = downloader.download(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");

        assertArrayEquals(IMAGE_BYTES, image.content());
    }

    @Test
    void rejectsPrivateAddressesAndCredentialsBeforeSendingTheRequest() {
        HttpImageUrlDownloader guarded = new HttpImageUrlDownloader(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                properties(),
                host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                HttpImageUrlDownloader::isPublicAddress);

        LlmInvocationException addressException = assertThrows(
                LlmInvocationException.class,
                () -> guarded.download(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png"));
        assertTrue(addressException.getMessage().contains("non-public"),
                addressException.getMessage());

        LlmInvocationException credentialException = assertThrows(
                LlmInvocationException.class,
                () -> downloader.download("https://user:pass@example.com/image.png"));
        assertTrue(credentialException.getMessage().contains("credentials"),
                credentialException.getMessage());
    }

    @Test
    void rejectsRedirectsThatResolveToPrivateAddresses() {
        HttpImageUrlDownloader guarded = new HttpImageUrlDownloader(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                properties(),
                host -> new InetAddress[] {InetAddress.getByName(
                        host.equals("private.example") ? "10.0.0.8" : "127.0.0.1")},
                HttpImageUrlDownloader::isPublicAddress);

        LlmInvocationException exception = assertThrows(
                LlmInvocationException.class,
                () -> guarded.download(
                        "http://127.0.0.1:" + server.getAddress().getPort()
                                + "/redirect-private"));
        assertTrue(exception.getMessage().contains("non-public"),
                exception.getMessage());
    }

    @Test
    void refusesNonHttpUrls() {
        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> downloader.download("file:///tmp/a.png"));

        assertTrue(exception.getMessage().contains("http or https"), exception.getMessage());
    }

    @Test
    void mapsHttpFailuresToLlmInvocationExceptions() {
        responseStatus = 404;

        LlmInvocationException exception = assertThrows(LlmInvocationException.class,
                () -> downloader.download(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png"));

        assertTrue(exception.getMessage().contains("HTTP 404"), exception.getMessage());
    }

    private static LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setImageTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
