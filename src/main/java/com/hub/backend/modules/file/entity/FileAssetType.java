package com.hub.backend.modules.file.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Describes why a stored asset exists, independent of its MIME type.
 */
public enum FileAssetType {

    /** A user-supplied source image for image editing or generation. */
    REFERENCE("reference"),

    /** A text-to-image generation result. */
    GENERATED("generated"),

    /** A result produced by the smart-editing tools. */
    EDIT_RESULT("edit_result"),

    /** A regular attachment uploaded for a conversation. */
    CHAT_ATTACHMENT("chat_attachment"),

    /** A user-uploaded profile avatar. */
    AVATAR("avatar");

    private final String value;

    FileAssetType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static FileAssetType fromValue(String value) {
        for (FileAssetType assetType : values()) {
            if (assetType.value.equals(value)) {
                return assetType;
            }
        }
        throw new IllegalArgumentException("Unsupported file asset type: " + value);
    }
}
