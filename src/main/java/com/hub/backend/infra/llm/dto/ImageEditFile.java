package com.hub.backend.infra.llm.dto;

/**
 * One binary part of an image-editing request.
 */
public record ImageEditFile(byte[] content, String fileName, String mimeType) {

    public ImageEditFile {
        content = content == null ? new byte[0] : content.clone();
    }
}
