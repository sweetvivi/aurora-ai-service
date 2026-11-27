package com.hub.backend.modules.admin.provider;

import com.hub.backend.modules.model.provider.LlmProviderEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "LlmProvider", description = "New API 渠道目录条目，不包含渠道密钥")
public record LlmProviderResponse(
        @Schema(description = "本地供应商 ID", example = "1") Long id,
        @Schema(description = "数据来源", example = "NEW_API") String source,
        @Schema(description = "New API 渠道 ID", example = "12") String externalId,
        @Schema(description = "稳定供应商 key", example = "newapi-channel-12") String providerKey,
        @Schema(description = "渠道展示名称") String name,
        @Schema(description = "供应商分类：CHAT / IMAGE / VIDEO / AUDIO / OTHER") String category,
        @Schema(description = "本地展示协议，固定为 OPENAI_COMPATIBLE") String protocol,
        @Schema(description = "渠道上游地址，仅用于目录展示") String baseUrl,
        @Schema(description = "视频成品下载源地址；为空表示仍从提交地址（中继）解析相对路径")
        String videoDownloadBaseUrl,
        @Schema(description = "该渠道是否已单独配置视频下载密钥；密钥本身永不返回")
        boolean videoDownloadApiKeySet,
        @Schema(description = "本地状态：ACTIVE / DISABLED") String status,
        @Schema(description = "New API 当前是否启用且属于配置分组") boolean upstreamAvailable,
        @Schema(description = "New API 渠道分组") String providerGroup,
        @Schema(description = "New API 渠道优先级") long priority,
        @Schema(description = "New API 渠道权重") int weight,
        @Schema(description = "排序号") int sortOrder,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "更新时间") LocalDateTime updatedAt
) {

    public static LlmProviderResponse of(LlmProviderEntity provider) {
        return new LlmProviderResponse(
                provider.getId(),
                provider.getSource(),
                provider.getExternalId(),
                provider.getProviderKey(),
                provider.getName(),
                provider.getCategory(),
                provider.getProtocol(),
                provider.getBaseUrl(),
                provider.getVideoDownloadBaseUrl(),
                provider.hasVideoDownloadApiKey(),
                provider.getStatus(),
                provider.isUpstreamAvailable(),
                provider.getProviderGroup(),
                provider.getPriority(),
                provider.getWeight(),
                provider.getSortOrder(),
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }
}
