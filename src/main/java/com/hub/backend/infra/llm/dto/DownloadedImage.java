package com.hub.backend.infra.llm.dto;

/**
 * Bytes downloaded from a generated-image URL and the response content type, when supplied.
 */
public record DownloadedImage(byte[] content, String mimeType) {
}
