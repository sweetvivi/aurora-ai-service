package com.hub.backend.modules.admin.commerce;

import com.hub.backend.common.config.properties.StorageProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.PayloadTooLargeException;
import com.hub.backend.common.exception.StorageException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 后台素材上传：工具图标与案例图。
 *
 * <p>为什么不复用 {@code FileService}：那套是按用户维度落库的附件（带 owner、配额、鉴权下载），
 * 而图标和案例图要能匿名出现在前台页面上。两件事的访问模型相反，混在一起只会互相拖累。</p>
 *
 * <p>落盘位置是 {@code app.storage.public-root} 下的 {@code commerce/}，由
 * {@code MediaResourceConfig} 映射成 {@code /media/commerce/<uuid>.<ext>}。
 * 文件名一律重新生成，客户端给的名字不参与任何路径拼接。</p>
 */
@Service
public class CommerceAssetService {

    /** 图标/案例图不需要大图，5MB 足够，也避免有人拿来当图床 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final String SUB_DIR = "commerce";
    private static final String URL_PREFIX = "/media/" + SUB_DIR + "/";

    /** MIME 与落盘扩展名的唯一映射：扩展名由类型决定，不采信文件名 */
    private static final Map<String, String> TYPE_TO_EXTENSION = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    private final Path root;

    public CommerceAssetService(StorageProperties properties) {
        this.root = Path.of(properties.getPublicRoot()).toAbsolutePath().normalize()
                .resolve(SUB_DIR);
    }

    public CommerceAssetResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PayloadTooLargeException("图片不能超过 5MB");
        }

        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
        String extension = TYPE_TO_EXTENSION.get(contentType);
        if (extension == null) {
            throw new InvalidRequestException("只支持 PNG / JPEG / WEBP / GIF 图片");
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            // UUID 拼出来的路径不可能越界，留着是因为这段逻辑以后会被复制
            throw new InvalidRequestException("非法的文件路径");
        }

        try {
            Files.createDirectories(root);
            try (InputStream source = file.getInputStream()) {
                // 先读签名再落盘：Content-Type 是客户端说了算的，扩展开头才做准
                byte[] head = source.readNBytes(12);
                verifySignature(head, extension);
                try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    output.write(head);
                    source.transferTo(output);
                }
            }
        } catch (IOException exception) {
            throw new StorageException("保存图片失败", exception);
        }

        return new CommerceAssetResponse(URL_PREFIX + fileName, file.getSize(), contentType);
    }

    /** 校验文件头，挡住「把 zip 改名成 png」这类 */
    private void verifySignature(byte[] head, String extension) {
        boolean ok = switch (extension) {
            case "png" -> head.length >= 4 && (head[0] & 0xFF) == 0x89
                    && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "jpg" -> head.length >= 3 && (head[0] & 0xFF) == 0xFF
                    && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "gif" -> head.length >= 4 && head[0] == 'G' && head[1] == 'I'
                    && head[2] == 'F' && head[3] == '8';
            case "webp" -> head.length >= 12 && head[0] == 'R' && head[1] == 'I'
                    && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
        if (!ok) {
            throw new InvalidRequestException("文件内容不是有效的图片");
        }
    }

    @Schema(name = "CommerceAsset", description = "上传后的素材地址")
    public record CommerceAssetResponse(
            @Schema(description = "可直接用于 iconUrl / gallery.url 的相对地址",
                    example = "/media/commerce/3f2a1c.png")
            String url,
            @Schema(description = "字节数") long size,
            @Schema(description = "图片 MIME 类型") String contentType
    ) {
    }
}
