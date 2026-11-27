package com.hub.backend.modules.admin.commerce;

import com.hub.backend.common.ApiError;
import com.hub.backend.common.logging.audit.AuditLog;
import com.hub.backend.modules.admin.provider.AdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 电商工具的素材上传：图标与案例图。
 *
 * <p>返回的 {@code url} 可以直接填进工具的 {@code iconUrl} 或 schema 的 {@code gallery[].url}，
 * 前台按相对路径就能访问（反代需要把 {@code /media} 转发到后端，见部署说明）。</p>
 */
// 类级默认：本控制器所有写接口都进审计（GET 由切面跳过），个别接口在方法上覆盖 action/id。
@AuditLog(target = "COMMERCE_ASSET")
@RestController
@RequestMapping("/api/admin/v1/commerce-assets")
@Tag(name = "电商素材", description = "工具图标与案例图上传")
public class CommerceAssetController {

    private final CommerceAssetService service;
    private final AdminGuard adminGuard;

    public CommerceAssetController(CommerceAssetService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @Operation(summary = "上传素材",
            description = """
                    只接受 PNG / JPEG / WEBP / GIF，单张不超过 5MB，会同时校验 MIME 与文件头。
                    文件名由服务端重新生成，返回的 url 形如 `/media/commerce/<uuid>.png`。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "不是有效图片或类型不支持",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "缺少或错误的管理密钥",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "图片超过 5MB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommerceAssetService.CommerceAssetResponse> upload(
            @RequestPart("file") MultipartFile file, HttpServletRequest httpRequest) {
        adminGuard.require(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.store(file));
    }
}
