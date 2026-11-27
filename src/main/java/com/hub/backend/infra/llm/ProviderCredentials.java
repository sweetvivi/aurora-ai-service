package com.hub.backend.infra.llm;

/**
 * Credentials and endpoint overrides for one upstream call.
 *
 * <p>Passed per call rather than read from configuration inside the adapter, because which supplier
 * serves a request is a property of the model being called and is decided in the business layer.</p>
 *
 * @param protocol   wire format of the supplier ({@code OPENAI}, {@code ANTHROPIC}, {@code GEMINI},
 *                   …), {@code null} or blank to assume an OpenAI-compatible gateway
 * @param baseUrl    base URL of the supplier, {@code null} to use the configured default
 * @param apiKey     key to send, {@code null} to use the configured default
 * @param chatPath   relative chat path of the supplier, {@code null} to use the configured default
 * @param imagesPath relative image path of the supplier, {@code null} to use the configured default
 * @param apiVersion optional API version, required by Azure
 * @param videosPath       relative submit path for video jobs, {@code null} for the service default
 * @param videoStatusPath  relative status path, {@code null} for the service default
 * @param videoResultBaseUrl origin a relative video result URL is resolved against
 * @param videoDownloadApiKey key that can fetch the finished video, when it differs from apiKey
 */
public record ProviderCredentials(String protocol, String baseUrl, String apiKey, String chatPath,
                                  String imagesPath, String apiVersion, String videosPath,
                                  String videoStatusPath, String videoResultBaseUrl,
                                  String videoDownloadApiKey) {

    /**
     * Credentials without video-specific settings.
     *
     * <p>Kept so chat, image and PPT suppliers read exactly as before: video only picks up the
     * extra fields when a supplier row actually declares them in its metadata.</p>
     */
    public ProviderCredentials(String protocol, String baseUrl, String apiKey, String chatPath,
                               String imagesPath, String apiVersion) {
        this(protocol, baseUrl, apiKey, chatPath, imagesPath, apiVersion,
                null, null, null, null);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean hasApiVersion() {
        return apiVersion != null && !apiVersion.isBlank();
    }

    public boolean hasVideosPath() {
        return videosPath != null && !videosPath.isBlank();
    }

    public boolean hasVideoStatusPath() {
        return videoStatusPath != null && !videoStatusPath.isBlank();
    }

    public boolean hasVideoResultBaseUrl() {
        return videoResultBaseUrl != null && !videoResultBaseUrl.isBlank();
    }

    public boolean hasVideoDownloadApiKey() {
        return videoDownloadApiKey != null && !videoDownloadApiKey.isBlank();
    }
}
