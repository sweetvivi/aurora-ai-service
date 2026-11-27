package com.hub.backend.modules.video;

import com.hub.backend.infra.llm.ProviderCredentials;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Decides where a finished video is fetched from and with which key.
 *
 * <p>A video job is submitted with the caller's New API relay key, and the status call answers with
 * a <em>host-less</em> path such as {@code /v1/videos/{id}/content}. Resolving that path against the
 * relay only works when the relay proxies artifact content; an xAI-style task plugin leaves the
 * relay unable to fetch it, so the file has to come from the supplier that produced it.</p>
 *
 * <p>That choice is data, never a constant: a supplier row may declare
 * {@code video_download_base_url}, which makes the relative path absolute against that origin and
 * makes the supplier's own key the one to download with. Without it nothing changes — the request
 * keeps using the relay and the caller's key, exactly as before.</p>
 */
final class ProviderVideoDownload {

    private ProviderVideoDownload() {
    }

    /**
     * @param relay    credentials built for the caller: relay base URL and relay key
     * @param provider supplier credentials, may be {@code null}
     * @return {@code relay} when the supplier declares no download origin, otherwise a copy of it
     *         carrying that origin and the supplier key used to fetch the file
     */
    static ProviderCredentials merge(ProviderCredentials relay, ProviderCredentials provider) {
        if (relay == null || provider == null || !provider.hasVideoResultBaseUrl()) {
            return relay;
        }
        return new ProviderCredentials(
                relay.protocol(),
                relay.baseUrl(),
                relay.apiKey(),
                relay.chatPath(),
                relay.imagesPath(),
                relay.apiVersion(),
                relay.videosPath(),
                relay.videoStatusPath(),
                provider.videoResultBaseUrl(),
                provider.videoDownloadApiKey());
    }

    /**
     * Content URL on the New API openai_video surface for a task this service submitted.
     *
     * <p>Status responses often carry a relative upstream uuid path
     * ({@code /v1/videos/{uuid}/content}). That path is not the New API task id and must not be
     * resolved against a supplier origin. Fetching {@code {base}{videosPath}/{taskId}/content}
     * with the caller's relay key lets the task plugin (xAI 1.1.1+) pull the bytes upstream.</p>
     *
     * @param baseUrl          relay base, typically ending in {@code /v1}
     * @param videosPath       submit path such as {@code /videos}; {@code null}/blank → {@code /videos}
     * @param providerTaskId   New API task id stored on {@code async_tasks.provider_task_id}
     */
    static String relayContentUrl(String baseUrl, String videosPath, String providerTaskId) {
        if (providerTaskId == null || providerTaskId.isBlank()) {
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = trimTrailingSlash(baseUrl.strip());
        String path = videosPath == null || videosPath.isBlank() ? "/videos" : videosPath.strip();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        path = trimTrailingSlash(path);
        return base + path + "/" + providerTaskId.strip() + "/content";
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * @return whether the resolved result URL belongs to the supplier origin, so the supplier key
     *         applies. A URL on any other host keeps the relay key: a supplier key sent to a third
     *         party would be a credential leak, not a fix.
     */
    static boolean usesProviderHost(ProviderCredentials credentials, String videoUrl) {
        if (credentials == null
                || !credentials.hasVideoDownloadApiKey()
                || !credentials.hasVideoResultBaseUrl()) {
            return false;
        }
        return sameOrigin(videoUrl, credentials.videoResultBaseUrl());
    }

    /** @return whether both values name the same {@code scheme://host:port} (default ports implied) */
    static boolean sameOrigin(String url, String base) {
        URI left = parse(url);
        URI right = parse(base);
        if (left == null || right == null || left.getHost() == null || right.getHost() == null) {
            return false;
        }
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private static URI parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.strip());
            return uri.getScheme() == null ? null : uri;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
    }
}
