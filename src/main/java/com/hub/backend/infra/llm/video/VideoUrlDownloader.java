package com.hub.backend.infra.llm.video;

import com.hub.backend.common.exception.LlmInvocationException;

/**
 * Port for downloading a generated video before it is stored locally.
 *
 * <p>Supplier links are usually temporary, so the bytes are pulled immediately and written into
 * the platform's own storage. Streaming into a temporary file rather than buffering matters here:
 * a clip is tens of megabytes and holding it in memory would be wasteful under concurrency.</p>
 */
public interface VideoUrlDownloader {

    /**
     * Downloads the video into a temporary file.
     *
     * @param url    result URL reported by the supplier
     * @param apiKey bearer token for gateways that serve the content behind their own auth;
     *               {@code null} when the URL is public
     * @throws LlmInvocationException when the URL is not a public http(s) address, the download
     *         fails, or the response size limit is exceeded
     */
    default DownloadedVideo download(String url, String apiKey) {
        return download(url, apiKey, null);
    }

    /**
     * Downloads the video into a temporary file.
     *
     * @param url           result URL reported by the supplier
     * @param apiKey        bearer token, {@code null} when the URL is public
     * @param trustedOrigin an origin an operator declared for this supplier, {@code null} when
     *                      there is none. Its host is exempt from the private-address rejection
     *                      for the same reason the configured relay is: it is a destination the
     *                      deployment vouched for, not one an upstream response invented. Without
     *                      it a supplier reachable only on an internal address could never have its
     *                      file fetched, and the declaration that says so is already data.
     * @throws LlmInvocationException when the URL is not a public http(s) address, the download
     *         fails, or the response size limit is exceeded
     */
    DownloadedVideo download(String url, String apiKey, String trustedOrigin);

    /**
     * @param path      temporary file holding the bytes; the caller owns it and should close it
     * @param sizeBytes number of bytes written
     * @param mimeType  Content-Type reported by the server, may be {@code null}
     */
    record DownloadedVideo(java.nio.file.Path path, long sizeBytes, String mimeType)
            implements AutoCloseable {

        @Override
        public void close() {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (java.io.IOException exception) {
                // Best effort: the system temp directory is reclaimed by the OS anyway.
            }
        }
    }
}
