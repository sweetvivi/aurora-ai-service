package com.hub.backend.modules.ppt.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class PptDtos {

    private PptDtos() {
    }

    @Schema(name = "PptCreateJobRequest", description = "创建 PPT 生成任务")
    public record CreateJobRequest(
            @Size(max = 2000) String topic,
            @Size(max = 100_000) String sourceText,
            List<String> fileIds,
            @Size(max = 16) String lang,
            @Size(max = 32) String pageRange,
            @Size(max = 64) String detail,
            @Size(max = 64) String scene,
            @Size(max = 64) String tone,
            @Size(max = 128) String textModelId,
            @Size(max = 128) String imageModelId,
            Boolean autoImg,
            /** light | mid | heavy — 插图密度，默认 mid */
            @Size(max = 16) String imgDensity,
            Boolean webSearch,
            @Size(max = 64) String templateId,
            /** agent（精品）| template（模板库） */
            @Size(max = 32) String generateMode,
            /** 官方 Deck code，generateMode=template 时必填 */
            @Size(max = 64) String deckTemplateId,
            @Size(max = 16) String aspectRatio
    ) {
    }

    @Schema(name = "PptCreateJobResponse")
    public record CreateJobResponse(
            String jobId,
            String status,
            int estimatedPoints
    ) {
    }

    @Schema(name = "PptJobDetail")
    public record JobDetailResponse(
            String jobId,
            String status,
            int progress,
            String message,
            String title,
            Integer pageCount,
            Integer pointsCost,
            List<String> previewUrls,
            String downloadUrl,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime finishedAt,
            String generateMode,
            String deckTemplateId,
            String stage,
            String progressMsg
    ) {
    }

    @Schema(name = "PptJobSummary")
    public record JobSummaryResponse(
            String jobId,
            String status,
            String title,
            Integer pageCount,
            String coverUrl,
            LocalDateTime createdAt,
            LocalDateTime finishedAt,
            String generateMode,
            String deckTemplateId
    ) {
    }

    @Schema(name = "PptDeckTemplate")
    public record DeckTemplateResponse(
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
            boolean commercialOk,
            int status,
            int sort
    ) {
    }

    @Schema(name = "PptUploadedFile")
    public record UploadedFileResponse(
            String fileId,
            String fileName,
            long size,
            String contentType
    ) {
    }

    @Schema(name = "PptTemplateSummary")
    public record TemplateSummaryResponse(
            String templateId,
            String name,
            String nameZh,
            String category,
            String description,
            String preview,
            List<String> scenes,
            int layoutCount
    ) {
    }

    @Schema(name = "PptTemplateDetail")
    public record TemplateDetailResponse(
            String templateId,
            String name,
            String nameZh,
            String category,
            String description,
            String preview,
            String master,
            List<String> scenes,
            List<String> layouts,
            int layoutCount
    ) {
    }

    @Schema(name = "PptOptimizePromptRequest")
    public record OptimizePromptRequest(
            @NotBlank @Size(max = 2000) String topic,
            @Size(max = 64) String scene,
            @Size(max = 16) String lang
    ) {
    }

    @Schema(name = "PptOptimizePromptResponse")
    public record OptimizePromptResponse(String optimizedTopic) {
    }

    @Schema(name = "PptEstimateRequest")
    public record EstimateRequest(
            @Size(max = 32) String pageRange,
            Boolean autoImg,
            /** light | mid | heavy — 插图密度，默认 mid */
            @Size(max = 16) String imgDensity,
            Boolean webSearch,
            @Size(max = 128) String textModelId
    ) {
    }

    @Schema(name = "PptEstimateResponse")
    public record EstimateResponse(
            int estimatedPoints,
            Map<String, Integer> breakdown
    ) {
    }
}
