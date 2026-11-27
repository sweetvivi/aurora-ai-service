package com.hub.backend.modules.ppt.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class PptTemplateAdminDtos {
    private PptTemplateAdminDtos() {}
    public record Request(@NotBlank @Size(max=64) String templateId, @NotBlank @Size(max=255) String name, @NotBlank @Size(max=128) String category, @Size(max=2048) String description, List<String> scenes, List<String> layouts, String themeJson, String previewUrl, @Pattern(regexp="DRAFT|PUBLISHED|DISABLED") String status) {}
    public record Response(Long id, String templateId, String name, String category, String description, List<String> scenes, List<String> layouts, String themeJson, String sourceFileName, String previewUrl, String status, LocalDateTime updatedAt) {}
}
