package com.hub.backend.modules.ppt.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class PptDeckAdminDtos {

    private PptDeckAdminDtos() {
    }

    @Schema(name = "PptDeckAdminRequest")
    public record Request(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String category,
            List<String> tags,
            @Size(max = 512) String styleBlurb,
            @Size(max = 512) String coverUrl,
            @Size(max = 16) String aspectRatio,
            Integer pageMin,
            Integer pageMax,
            Integer pageDefault,
            @Size(max = 32) String renderer,
            @Size(max = 64) String themeTemplateId,
            String specJson,
            @Size(max = 512) String sourcePptxKey,
            @Size(max = 256) String licenseSource,
            Boolean commercialOk,
            Integer sort,
            Integer status
    ) {
    }

    @Schema(name = "PptDeckAdminResponse")
    public record Response(
            Long id,
            String code,
            String name,
            String category,
            List<String> tags,
            String styleBlurb,
            String coverUrl,
            String aspectRatio,
            int pageMin,
            int pageMax,
            int pageDefault,
            String renderer,
            String themeTemplateId,
            String specJson,
            String sourcePptxKey,
            String licenseSource,
            boolean commercialOk,
            int sort,
            int status
    ) {
    }

    @Schema(name = "PptDeckUploadSourceResponse")
    public record UploadSourceResponse(String url, String key) {
    }
}
