package com.hub.backend.modules.chat.dto;

/** Server-only baseline for revising the latest generated MD, XLSX or DOCX artifact. */
public record ChatArtifactContext(String toolName, String arguments) {
}
