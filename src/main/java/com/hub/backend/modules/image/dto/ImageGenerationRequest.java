package com.hub.backend.modules.image.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/images/generations}.
 *
 * @param prompt         what to draw
 * @param model          image model key, defaults to {@code app.llm.image-model}
 * @param size           {@code WxH} or {@code auto}; left to the gateway when absent
 * @param quality        {@code low} / {@code medium} / {@code high} / {@code auto}
 * @param count          how many images to produce, 1 to 4
 * @param conversationId optional thread to attach the results to, validated against the owner
 */
@Schema(name = "ImageGenerationRequest", description = "图片生成请求")
public record ImageGenerationRequest(
        @Schema(description = "提示词", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "一只戴帽子的猫，极简插画")
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 4000, message = "prompt must be at most 4000 characters")
        String prompt,

        @Schema(description = "图像模型 key，缺省取 app.llm.image-model", example = "gpt-image-2")
        @Size(max = 128, message = "model must be at most 128 characters")
        String model,

        @Schema(description = "尺寸，如 1024x1024；auto 由上游决定", example = "1024x1024")
        @Pattern(regexp = "auto|[1-9][0-9]{0,4}x[1-9][0-9]{0,4}",
                message = "size must be 'auto' or a WxH pair")
        String size,

        @Schema(description = "质量档位", example = "medium")
        @Pattern(regexp = "auto|low|medium|high", message = "quality must be auto, low, medium or high")
        String quality,

        @Schema(description = "生成张数，1-4", example = "1")
        @Min(value = 1, message = "count must be at least 1")
        @Max(value = 4, message = "count must be at most 4")
        Integer count,

        @Schema(description = "可选：把生成的图片挂到该会话")
        Long conversationId,

        @Schema(description = """
                可选：产出该图的功能，用于「我的作品」按页归属。
                取值 image_generation / painting / smart_edit / canvas / chat / agent；
                留空按 image_generation 处理。该值只影响作品列表的归属，不影响计费与权限。
                """, example = "painting")
        @Size(max = 32, message = "source must be at most 32 characters")
        String source
) {

    private static final int DEFAULT_COUNT = 1;

    public String modelOrDefault(String fallback) {
        return model == null || model.isBlank() ? fallback : model.strip();
    }

    public String sizeOrNull() {
        return size == null || size.isBlank() ? null : size.strip();
    }

    public String qualityOrNull() {
        return quality == null || quality.isBlank() ? null : quality.strip();
    }

    public int countOrDefault() {
        return count == null ? DEFAULT_COUNT : count;
    }
}