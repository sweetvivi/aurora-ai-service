package com.hub.backend.modules.admin.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code PUT /api/admin/v1/models/batch}.
 *
 * <p>Every presentation field is optional and {@code null} means "leave unchanged", exactly as
 * {@code PUT /models/{id}/display}. {@code status} additionally carries the visibility switch so
 * one call can flip both the hidden flag and the display fields of many models.</p>
 *
 * <p>{@code sortOrder} is submitted per row by the caller (selection order plus a starting value);
 * the service never renumbers implicitly, so the result stays predictable.</p>
 */
@Schema(name = "AdminModelBatchUpdateRequest",
        description = "批量修改模型显示状态与展示字段；字段为 null 表示不修改该字段")
public record AdminModelBatchUpdateRequest(
        @Schema(description = "要修改的模型，最多 100 条",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "[{\"modelId\":23}]")
        @NotEmpty(message = "items must not be empty")
        @Size(max = MAX_BATCH_ITEMS, message = "items must contain at most 100 entries")
        @Valid
        List<Item> items
) {

    /** Upper bound of one batch, so a single call cannot turn into an unbounded write. */
    public static final int MAX_BATCH_ITEMS = 100;

    /**
     * One model's new values.
     *
     * <p>Empty strings are meaningful for {@code company} / {@code iconUrl} / {@code description}
     * (they clear the column) and for {@code thinkingParameter} (it removes the capability entry);
     * empty {@code thinkingOnValue} / {@code thinkingOffValue} restore boolean defaults; only
     * {@code null} skips a field.</p>
     */
    @Schema(name = "AdminModelBatchUpdateItem",
            description = "单个模型的修改内容；字段为 null 表示不修改")
    public record Item(
            @Schema(description = "模型 ID", example = "23")
            @NotNull(message = "modelId must not be null") Long modelId,
            @Schema(description = "本地显示状态：ACTIVE 显示，DISABLED 隐藏；null 表示不改显隐",
                    example = "ACTIVE")
            @Pattern(regexp = "ACTIVE|DISABLED",
                    message = "status must be ACTIVE or DISABLED") String status,
            @Schema(description = "展示名称；空字符串非法") @Size(max = 128) String modelName,
            @Schema(description = "厂商；空字符串表示清空") @Size(max = 128) String company,
            @Schema(description = "图标地址；空字符串表示清空") @Size(max = 1024) String iconUrl,
            @Schema(description = "模型说明；空字符串表示清空") @Size(max = 1024) String description,
            @Schema(description = "排序号，由前端按勾选顺序算好逐条下发")
            @Min(value = 0, message = "sortOrder must not be negative") Integer sortOrder,
            @Schema(description = "是否声明支持思考模式", example = "false") Boolean thinking,
            @Schema(description = "开启思考时写入上游请求的字段名；空字符串清除配置",
                    example = "enable_thinking") @Size(max = 64) String thinkingParameter,
            @Schema(description = "开启思考时写入上游的值；空字符串恢复默认 true",
                    example = "true") @Size(max = 64) String thinkingOnValue,
            @Schema(description = "关闭思考时写入上游的值；空字符串恢复默认 false",
                    example = "false") @Size(max = 64) String thinkingOffValue,
            @Schema(description = "是否支持上游原生联网搜索", example = "false")
            Boolean supportsWebSearch
    ) {
    }
}
