package com.hub.backend.modules.banner;

import com.fasterxml.jackson.databind.node.NullNode;
import com.hub.backend.common.ApiError;
import com.hub.backend.modules.appconfig.ChannelResolver;
import com.hub.backend.modules.banner.dto.BannerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public banner lookup used by the conversation page.
 */
@RestController
@RequestMapping("/api/v1/banners")
@Tag(name = "顶部 Banner", description = "当前站点生效中的活动 Banner")
public class PublicBannerController {

    private static final String CACHE_CONTROL = "public, max-age=30, stale-while-revalidate=60";

    private final BannerService bannerService;
    private final ChannelResolver channelResolver;

    public PublicBannerController(BannerService bannerService, ChannelResolver channelResolver) {
        this.bannerService = bannerService;
        this.channelResolver = channelResolver;
    }

    @Operation(summary = "当前生效 Banner",
            description = """
                    站点按 `app-name` 请求头、请求 Host、默认站点依次解析。
                    站点专属 Banner 优先于全局 Banner，再按排序号和 ID 选择一条。
                    没有符合条件的记录时返回 `200` 和 JSON `null`。
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前 Banner，或 null"),
            @ApiResponse(responseCode = "404", description = "指定的站点不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/current")
    public ResponseEntity<Object> current(HttpServletRequest request) {
        Long siteId = channelResolver.resolve(request).getId();
        BannerResponse banner = bannerService.current(siteId);

        Object body = banner == null ? NullNode.getInstance() : banner;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(body);
    }
}
