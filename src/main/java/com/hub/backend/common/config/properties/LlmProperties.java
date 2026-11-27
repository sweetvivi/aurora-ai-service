package com.hub.backend.common.config.properties;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Connection settings for the upstream OpenAI-compatible model gateway.
 *
 * <p>The values are bound from {@code app.llm.*}. A service-wide legacy credential is optional and
 * must be supplied through {@code APP_LLM_API_KEY} rather than committed to the repository.</p>
 */
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /**
     * Base URL of the gateway, including the API version segment (for a New API relay that is
     * {@code <origin>/v1}).
     *
     * <p>{@code application.yml} derives this from {@code NEW_API_BASE_URL + /v1} unless
     * {@code LLM_BASE_URL} overrides it, so a deployment that only knows the New API origin still
     * gets a usable relay address. A value that is present but not an absolute http(s) URL is
     * rejected at startup; a blank one is reported by whichever call needs it.</p>
     */
    private String baseUrl = "";

    /** Bearer token sent in the {@code Authorization} header. */
    private String apiKey = "";

    /**
     * Optional key used for image endpoints when it differs from {@link #apiKey}.
     *
     * <p>Upstream gateways routinely scope a key to one model group: a chat key can answer
     * {@code chat/completions} but returns 404 {@code model_not_found} for an image model.
     * Blank means "use {@link #apiKey}".</p>
     */
    private String imageApiKey = "";

    /** Model key used when a request does not specify one. */
    private String defaultModel = "deepseek-v4.1-flash";

    /**
     * When false (default), a model key that is not in the visible catalogue is rejected instead of
     * being forwarded raw to the upstream gateway. Keep true only while onboarding a brand-new
     * upstream model before the catalogue row exists.
     */
    private boolean allowUnlistedModels = false;

    /** Relative path of the chat completion endpoint. */
    private String chatCompletionsPath = "/chat/completions";

    /** TCP connect timeout. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Per-request timeout covering connect plus response reading. */
    private Duration readTimeout = Duration.ofSeconds(120);

    /** Relative path of the image generation endpoint. */
    private String imagesPath = "/images/generations";

    /** Relative path used to ask a gateway which models it offers. */
    private String modelsPath = "/models";

    /** Image model key used when a request does not specify one. */
    private String imageModel = "gpt-image-2";

    /**
     * Image generation timeout. Considerably longer than a chat turn: observed upstream latency is
     * over a minute before a result, successful or not.
     */
    private Duration imageTimeout = Duration.ofMinutes(4);

    /** Relative path of the async video generation endpoint. */
    private String videosPath = "/videos";

    /** Video model key used when a request does not specify one. */
    private String videoModel = "";

    /**
     * Relative path used to poll a submitted video task.
     *
     * <p>Separate from {@link #videosPath} when a relay exposes different submit and status
     * routes. New API's unified {@code GET /v1/videos/{id}} often reports status only; the xAI
     * plugin's {@code GET /xai/v1/videos/{id}} carries the finished file URL. Blank reuses
     * {@link #videosPath}.</p>
     */
    private String videoStatusPath = "/xai/v1/videos";

    /**
     * Origin used to turn a relative result URL into a downloadable one.
     *
     * <p>Relays that proxy a supplier often answer with a path rather than a URL:
     * {@code {"video":{"url":"/v1/videos/<upstream id>/content"}}}. When that path belongs to the
     * supplier rather than to the relay, resolving it against the configured base URL would point
     * at the relay, whose own {@code /content} route is served by a store it may have disabled.
     * Set this to the supplier origin in that case. Blank resolves against the base URL, i.e. the
     * old behaviour.</p>
     */
    private String videoResultBaseUrl = "";

    /**
     * Fallback bearer token used to download the finished video.
     *
     * <p>Only consulted when the supplier row declares no {@code video_download_api_key} of its
     * own, which is now the primary place for it: that column is not reset by the catalogue sync,
     * whereas {@code api_key} on the same row is. Kept as the last fallback so an existing
     * deployment that sets this variable keeps working unchanged.</p>
     *
     * <p>Ignored when the download goes through the relay with {@code app.new-api.enabled} true:
     * the relay content URL requires the same user token that submitted the job.</p>
     */
    private String videoDownloadApiKey = "";

    /**
     * Timeout of a single video HTTP call.
     *
     * <p>Not the duration of a generation: the endpoint answers immediately with a task id and the
     * result is polled afterwards, so only the submit and status requests have to fit in here.</p>
     */
    private Duration videoTimeout = Duration.ofMinutes(2);

    /**
     * Timeout of the finished-video download.
     *
     * <p>Separate from {@link #videoTimeout}: a supplier that generates on demand streams the first
     * request for a fresh file from cold storage, and that first byte can take minutes. Treating
     * it as a submit timeout aborts a job whose bytes already exist.</p>
     */
    private Duration videoDownloadTimeout = Duration.ofMinutes(10);

    /**
     * Asset host that turns an inline image into a public URL, e.g. {@code https://host/v1/uploads}.
     *
     * <p>Reason references are uploaded instead of inlined: several suppliers only accept
     * {@code https} links for reference media and silently ignore (or reject) base64 data URLs.
     * Blank keeps the old behaviour — the image travels inline as a data URL.</p>
     */
    private String videoUploadUrl = "";

    /** Bearer token for {@link #videoUploadUrl}; blank sends no Authorization header. */
    private String videoUploadApiKey = "";

    /**
     * Video models whose reference images also have to be sent as {@code input_reference}.
     *
     * <p>Only the OpenAI Videos / sora style task plugins read that field, and their semantics for
     * it are "the frame the clip starts from" — not "a reference". The xAI task plugin promotes it
     * verbatim: {@code if (source.image === undefined && source.input_reference !== undefined)
     * source.image = source.input_reference}, so a reference-mode request that carries
     * {@code input_reference} silently becomes a first-frame request and the video starts from the
     * reference image. That is exactly the bug this list exists to avoid, so it stays empty by
     * default: {@code reference_images}, {@code image_urls} and the Ark {@code content[]} roles
     * reach upstream on their own.</p>
     *
     * <p>List a model here only when its channel is answered by a plugin that drops all of those
     * and keeps nothing but {@code input_reference} — accepting that this model then has no true
     * reference mode, only a first frame. Comma separated, e.g.
     * {@code LLM_VIDEO_INPUT_REFERENCE_MODELS=seedance-2.0}.</p>
     */
    private List<String> videoInputReferenceModels = List.of();

    /** Optional OpenAI-compatible speech-to-text model; blank keeps audio input disabled. */
    private String audioTranscriptionModel = "";

    /** Relative path of the OpenAI-compatible speech-to-text endpoint. */
    private String audioTranscriptionsPath = "/audio/transcriptions";

    /**
     * Fails startup when the derived relay address is unusable.
     *
     * <p>{@code app.llm.base-url} is normally {@code NEW_API_BASE_URL + /v1}. A deployment that
     * forgot both would otherwise boot fine and only fail on the first user request, which is far
     * harder to attribute than a refused start.</p>
     */
    @PostConstruct
    void validateBaseUrl() {
        resolvedBaseUrl();
    }

    public String resolvedBaseUrl() {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // The New API origin is derived by appending /v1, so a missing NEW_API_BASE_URL would
        // otherwise surface as the relative string "/v1" and fail at the first model call instead
        // of at startup. Reject it here while the message can still name the cause.
        if (!trimmed.isEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalStateException(
                    "app.llm.base-url must be an absolute http(s) URL, got '" + trimmed
                            + "'. Set LLM_BASE_URL, or NEW_API_BASE_URL with a scheme such as "
                            + "http://new-api:3000");
        }
        return trimmed;
    }

    public String resolvedChatCompletionsUrl() {
        String path = chatCompletionsPath == null ? "" : chatCompletionsPath.trim();
        if (path.isEmpty()) {
            return resolvedBaseUrl();
        }
        return path.startsWith("/") ? resolvedBaseUrl() + path : resolvedBaseUrl() + "/" + path;
    }

    public String resolvedImagesUrl() {
        String path = imagesPath == null ? "" : imagesPath.trim();
        if (path.isEmpty()) {
            return resolvedBaseUrl();
        }
        return path.startsWith("/") ? resolvedBaseUrl() + path : resolvedBaseUrl() + "/" + path;
    }

    public String resolvedVideosUrl() {
        String path = videosPath == null ? "" : videosPath.trim();
        if (path.isEmpty()) {
            return resolvedBaseUrl();
        }
        return path.startsWith("/") ? resolvedBaseUrl() + path : resolvedBaseUrl() + "/" + path;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    /** Image endpoint key; falls back to {@link #getApiKey()} when not configured. */
    public String resolveImageApiKey() {
        return imageApiKey == null || imageApiKey.isBlank() ? apiKey : imageApiKey;
    }

    public String getImageApiKey() {
        return imageApiKey;
    }

    public void setImageApiKey(String imageApiKey) {
        this.imageApiKey = imageApiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public boolean isAllowUnlistedModels() {
        return allowUnlistedModels;
    }

    public void setAllowUnlistedModels(boolean allowUnlistedModels) {
        this.allowUnlistedModels = allowUnlistedModels;
    }

    public String getChatCompletionsPath() {
        return chatCompletionsPath;
    }

    public void setChatCompletionsPath(String chatCompletionsPath) {
        this.chatCompletionsPath = chatCompletionsPath;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getImagesPath() {
        return imagesPath;
    }

    public void setImagesPath(String imagesPath) {
        this.imagesPath = imagesPath;
    }

    public String getModelsPath() {
        return modelsPath;
    }

    public void setModelsPath(String modelsPath) {
        this.modelsPath = modelsPath;
    }

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public Duration getImageTimeout() {
        return imageTimeout;
    }

    public void setImageTimeout(Duration imageTimeout) {
        this.imageTimeout = imageTimeout;
    }

    public String getVideosPath() {
        return videosPath;
    }

    public void setVideosPath(String videosPath) {
        this.videosPath = videosPath;
    }

    public String getVideoModel() {
        return videoModel;
    }

    public void setVideoModel(String videoModel) {
        this.videoModel = videoModel;
    }

    public String getVideoStatusPath() {
        return videoStatusPath;
    }

    public void setVideoStatusPath(String videoStatusPath) {
        this.videoStatusPath = videoStatusPath;
    }

    public String getVideoResultBaseUrl() {
        return videoResultBaseUrl;
    }

    public void setVideoResultBaseUrl(String videoResultBaseUrl) {
        this.videoResultBaseUrl = videoResultBaseUrl;
    }

    public String getVideoDownloadApiKey() {
        return videoDownloadApiKey;
    }

    public void setVideoDownloadApiKey(String videoDownloadApiKey) {
        this.videoDownloadApiKey = videoDownloadApiKey;
    }

    /**
     * @return the configured result origin without a trailing slash, or the base URL when unset.
     */
    public String resolvedVideoResultBaseUrl() {
        String trimmed = videoResultBaseUrl == null ? "" : videoResultBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? resolvedBaseUrl() : trimmed;
    }

    public Duration getVideoTimeout() {
        return videoTimeout;
    }

    public void setVideoTimeout(Duration videoTimeout) {
        this.videoTimeout = videoTimeout;
    }

    public Duration getVideoDownloadTimeout() {
        return videoDownloadTimeout;
    }

    public void setVideoDownloadTimeout(Duration videoDownloadTimeout) {
        this.videoDownloadTimeout = videoDownloadTimeout;
    }

    /**
     * @return the asset upload endpoint, or {@code null} when inline data URLs should keep being
     *         used
     */
    public String resolvedVideoUploadUrl() {
        String trimmed = videoUploadUrl == null ? "" : videoUploadUrl.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String getVideoUploadUrl() {
        return videoUploadUrl;
    }

    public void setVideoUploadUrl(String videoUploadUrl) {
        this.videoUploadUrl = videoUploadUrl;
    }

    public String getVideoUploadApiKey() {
        return videoUploadApiKey;
    }

    public void setVideoUploadApiKey(String videoUploadApiKey) {
        this.videoUploadApiKey = videoUploadApiKey;
    }

    public List<String> getVideoInputReferenceModels() {
        return videoInputReferenceModels;
    }

    public void setVideoInputReferenceModels(List<String> videoInputReferenceModels) {
        this.videoInputReferenceModels = videoInputReferenceModels;
    }

    public String getAudioTranscriptionModel() {
        return audioTranscriptionModel;
    }

    public void setAudioTranscriptionModel(String audioTranscriptionModel) {
        this.audioTranscriptionModel = audioTranscriptionModel;
    }

    public String resolvedAudioTranscriptionsUrl() {
        String path = audioTranscriptionsPath == null ? "" : audioTranscriptionsPath.trim();
        if (path.isEmpty()) return resolvedBaseUrl();
        return path.startsWith("/") ? resolvedBaseUrl() + path : resolvedBaseUrl() + "/" + path;
    }

    public String getAudioTranscriptionsPath() {
        return audioTranscriptionsPath;
    }

    public void setAudioTranscriptionsPath(String audioTranscriptionsPath) {
        this.audioTranscriptionsPath = audioTranscriptionsPath;
    }
}
