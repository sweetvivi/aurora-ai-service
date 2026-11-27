package com.hub.backend.modules.file.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which feature produced a stored attachment.
 *
 * <p>This is the owning <em>page</em>, not the storage purpose: several features write
 * {@link FileAssetType#GENERATED} (the image-generation page, AI drawing, the canvas), and a work
 * list has to tell them apart or every gallery shows every picture of the account. See
 * {@code V77__uploaded_file_source.sql} for the reasoning and the backfill.</p>
 *
 * <p>Rows created before the column existed keep {@code null}; a list filtered by a source never
 * treats those as its own.</p>
 */
public enum FileSource {

    /** The image-generation page ({@code /image-generation}). */
    IMAGE_GENERATION("image_generation"),

    /** AI drawing ({@code /painting}): compositions built as a conversation titled {@code AI绘画：…}. */
    PAINTING("painting"),

    /** The smart-editing workbench: matting, removal, inpainting, enlarge, retouch. */
    SMART_EDIT("smart_edit"),

    /** AI canvas: storyboard frames and canvas-side generation. */
    CANVAS("canvas"),

    /** Pictures generated inside a chat thread by the chat page's image command. */
    CHAT("chat"),

    /** Pictures produced by an agent tool call. */
    AGENT("agent");

    private final String value;

    FileSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Lenient lookup for a value that arrives from a client.
     *
     * @return the matching source, or {@code null} when the value is blank or unknown. Callers decide
     *         whether an unknown value is an error (the HTTP layer rejects it) or simply "unset"
     *         (internal callers that legitimately have no source).
     */
    public static FileSource fromValueOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        for (FileSource source : values()) {
            if (source.value.equals(normalized)) {
                return source;
            }
        }
        return null;
    }

    /** True when {@code value} names a source this build knows about. */
    public static boolean isKnown(String value) {
        return fromValueOrNull(value) != null;
    }
}
