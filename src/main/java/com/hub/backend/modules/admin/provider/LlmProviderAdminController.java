package com.hub.backend.modules.admin.provider;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.catalog.NewApiCatalogSyncResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * New API channel catalogue for the admin console.
 *
 * <p>Read-only apart from two fields, both about the same hop: where a finished video is downloaded
 * from and which key that host accepts. Neither is something New API publishes — they are
 * operator data about the supplier sitting behind the relay — so they need a write path, and they
 * are written one field per call so an edit cannot rewrite the synced part of the row.</p>
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "LLM_PROVIDER")
@RestController
@RequestMapping("/api/admin/v1/providers")
@Tag(name = "供应商管理", description = "New API 渠道目录，同步只读；视频下载源地址与下载密钥可单独设置")
public class LlmProviderAdminController {

    private final LlmProviderAdminService providerService;
    private final AdminGuard adminGuard;

    public LlmProviderAdminController(LlmProviderAdminService providerService,
                                      AdminGuard adminGuard) {
        this.providerService = providerService;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "同步 New API 渠道目录",
            description = "重新读取 New API 匿名模型广场并同步本地目录；不会读取或保存渠道密钥。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "同步完成"),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "New API 不可用或公开模型广场读取失败",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/sync")
    public NewApiCatalogSyncResponse sync(HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return providerService.sync();
    }

    @Operation(summary = "渠道列表",
            description = "支持按协议、本地状态和分类过滤；返回结果不含渠道密钥。")
    @GetMapping
    public List<LlmProviderResponse> list(
            @Parameter(description = "按协议筛选，例如 OPENAI_COMPATIBLE")
            @RequestParam(required = false) String protocol,
            @Parameter(description = "按本地状态筛选：ACTIVE / DISABLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "按分类筛选：CHAT / IMAGE / VIDEO / AUDIO / OTHER")
            @RequestParam(required = false) String category,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return providerService.list(protocol, status, category);
    }

    @Operation(summary = "渠道详情")
    @GetMapping("/{id}")
    public LlmProviderResponse detail(@PathVariable Long id, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return providerService.get(id);
    }

    @Operation(summary = "设置视频下载源地址",
            description = """
                    单独设置一个字段：视频任务的状态接口只返回不含主机名的相对路径
                    （如 /v1/videos/{id}/content）。该路径属于产出文件的供应商，而不是提交用的中继。
                    填了绝对地址后，本站会直接从该地址下载成品，并用同一供应商行的密钥鉴权；
                    传空值清除设置，恢复为按提交地址（中继）解析。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已保存"),
            @ApiResponse(responseCode = "400", description = "不是绝对的 http(s) 地址",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "供应商不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}/video-download-base-url")
    public LlmProviderResponse updateVideoDownloadBaseUrl(
            @PathVariable Long id,
            @RequestBody VideoDownloadBaseUrlRequest body,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return providerService.updateVideoDownloadBaseUrl(id, body == null ? null : body.value());
    }

    @Schema(name = "ProviderVideoDownloadBaseUrlRequest",
            description = "视频成品下载源地址；minLength 未设，空字符串表示清除")
    public record VideoDownloadBaseUrlRequest(
            @Schema(description = "绝对 http(s) 地址，例如 https://api.example.com；为空表示清除",
                    example = "https://api.example.com", nullable = true) String value) {
    }

    @Operation(summary = "设置视频下载密钥",
            description = """
                    单独设置一个字段：与「视频下载源地址」配对使用，用于直连供应商取件时的鉴权。
                    该列不受目录同步影响（同步只清空 api_key），因此可以长期保存，切换供应商不需要重新部署。
                    传空值清除，回落顺序为：本列 → 该行提交密钥 → 环境变量 LLM_VIDEO_DOWNLOAD_API_KEY。
                    响应只返回 videoDownloadApiKeySet 标记，密钥本身永不回显。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已保存"),
            @ApiResponse(responseCode = "400", description = "含空白字符或带了 Bearer 前缀",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "供应商不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}/video-download-api-key")
    public LlmProviderResponse updateVideoDownloadApiKey(
            @PathVariable Long id,
            @RequestBody VideoDownloadApiKeyRequest body,
            HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return providerService.updateVideoDownloadApiKey(id, body == null ? null : body.value());
    }

    @Schema(name = "ProviderVideoDownloadApiKeyRequest",
            description = "视频下载密钥原文，不要带 Bearer 前缀；空字符串表示清除")
    public record VideoDownloadApiKeyRequest(
            @Schema(description = "供应商可直连取件的密钥；为空表示清除并回落",
                    example = "sk-...", nullable = true) String value) {
    }
}
