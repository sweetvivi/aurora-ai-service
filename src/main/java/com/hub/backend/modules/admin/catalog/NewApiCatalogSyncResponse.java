package com.hub.backend.modules.admin.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "NewApiCatalogSync", description = "New API 渠道和模型目录同步结果")
public record NewApiCatalogSyncResponse(
        @Schema(description = "同步来源", example = "NEW_API") String source,
        @Schema(description = "同步时间") Instant syncedAt,
        @Schema(description = "New API 渠道总数") int providersTotal,
        @Schema(description = "新建的本地渠道数") int providersCreated,
        @Schema(description = "更新的本地渠道数") int providersUpdated,
        @Schema(description = "当前不可用渠道数") int providersUnavailable,
        @Schema(description = "当前可用渠道引用的模型总数") int modelsTotal,
        @Schema(description = "新建的本地模型数") int modelsCreated,
        @Schema(description = "更新的本地模型数") int modelsUpdated,
        @Schema(description = "当前不可用模型数") int modelsUnavailable,
        @Schema(description = "成功实时读取上游模型列表的可用渠道数") int providersLive,
        @Schema(description = "实时读取失败并回退到渠道配置的可用渠道数") int providersFallback,
        @Schema(description = "成功回写渠道模型列表的渠道数") int providersWriteBackSucceeded,
        @Schema(description = "回写渠道模型列表失败的渠道数") int providersWriteBackFailed,
        @Schema(description = "查询过价格快照的当前模型数") int pricingChecked,
        @Schema(description = "找到唯一同家族价格模板的缺价模型数") int pricingCopied,
        @Schema(description = "成功写入 New API 价格的新模型数") int pricingCreated,
        @Schema(description = "价格预览或写入失败的模型数") int pricingFailed,
        @Schema(description = "同步结束后仍没有明确价格的模型数") int pricingUnpriced
) {
}
