package com.hub.backend.modules.model;

import java.util.Locale;

/**
 * Default {@code capabilities_json} seeds for VIDEO models discovered by New API sync.
 *
 * <p>Sync only fills blanks — never overwrites an operator-edited declaration. Limits follow the
 * current xAI Imagine Video docs where the model name is recognisable; everything else gets a
 * conservative multi-ref default so the admin list already shows a useful「最多参考图」.</p>
 */
final class VideoCapabilityDefaults {

    /** grok-imagine-video-1.5 family: up to 14 reference images. */
    static final int GROK_1_5_MAX_REFERENCE_IMAGES = 14;

    /** grok-imagine-video (non-1.5): up to 7 reference images. */
    static final int GROK_MAX_REFERENCE_IMAGES = 7;

    /** Unknown VIDEO vendors: enough for reference2video without claiming a supplier limit. */
    static final int GENERIC_MAX_REFERENCE_IMAGES = 4;

    private VideoCapabilityDefaults() {
    }

    /**
     * @return suggested max reference images for a model key; never below 1
     */
    static int maxReferenceImages(String modelKey) {
        String key = modelKey == null ? "" : modelKey.strip().toLowerCase(Locale.ROOT);
        if (key.contains("grok-imagine-video-1.5") || key.contains("imagine-video-1.5")) {
            return GROK_1_5_MAX_REFERENCE_IMAGES;
        }
        if (key.contains("grok-imagine-video") || key.contains("grok")) {
            return GROK_MAX_REFERENCE_IMAGES;
        }
        return GENERIC_MAX_REFERENCE_IMAGES;
    }

    /**
     * Minimal capabilities document so the admin UI and video dock have something to show
     * before an operator customises modes / ratios.
     */
    static String capabilitiesJson(String modelKey) {
        int maxRefs = maxReferenceImages(modelKey);
        // reference2video is included when the default allows more than one image; otherwise the
        // dock would offer a tab that immediately hits the one-image ceiling.
        String modes = maxRefs > 1
                ? "[\"text2video\",\"image2video\",\"reference2video\"]"
                : "[\"text2video\",\"image2video\"]";
        return """
                {"modes":%s,"aspectRatios":["16:9","9:16","1:1"],"resolutions":["720p","1080p"],"durations":[5,10],"maxReferenceImages":%d,"audio":false}
                """.formatted(modes, maxRefs).strip();
    }
}
