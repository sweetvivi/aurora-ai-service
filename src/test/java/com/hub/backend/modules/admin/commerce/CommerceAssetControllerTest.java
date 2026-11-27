package com.hub.backend.modules.admin.commerce;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台素材上传。
 *
 * <p>这一层的风险集中在「客户端说了算」的字段：Content-Type 和文件名都可以随便写，
 * 所以测试重点不是上传成功，而是拒绝伪装成图片的文件。</p>
 */
@SpringBootTest(properties = {
        "app.admin.api-key=admin-test-key",
        "app.storage.public-root=target/test-public"
})
@AutoConfigureMockMvc
class CommerceAssetControllerTest {

    private static final String ADMIN_KEY = "admin-test-key";
    private static final String URL = "/api/admin/v1/commerce-assets";

    /** 1x1 透明 PNG */
    private static final byte[] PNG_1PX = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final Path PUBLIC_ROOT = Path.of("target/test-public");

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void cleanUp() throws IOException {
        Path commerceDir = PUBLIC_ROOT.resolve("commerce");
        if (!Files.exists(commerceDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(commerceDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 测试清理失败不影响断言结果
                }
            });
        }
    }

    @Test
    void rejectsRequestsWithoutTheAdminKey() throws Exception {
        mockMvc.perform(multipart(URL).file(pngFile()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void storesAnImageAndReturnsAPublicUrl() throws Exception {
        String body = mockMvc.perform(multipart(URL)
                        .file(pngFile())
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"/media/commerce/"), body);
        // 文件名由服务端生成，客户端给的 a.png 不该出现在返回里
        assertTrue(!body.contains("/a.png"), body);

        long stored = Files.list(PUBLIC_ROOT.resolve("commerce")).count();
        assertTrue(stored == 1, "expected exactly one stored file");
    }

    @Test
    void rejectsFilesWhoseContentIsNotAnImage() throws Exception {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "a.png", "image/png", "not really a png".getBytes());

        mockMvc.perform(multipart(URL).file(fake).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsupportedContentTypes() throws Exception {
        MockMultipartFile svg = new MockMultipartFile(
                "file", "a.svg", "image/svg+xml", "<svg/>".getBytes());

        // SVG 可以作为脚本载体，这里只收光栅图
        mockMvc.perform(multipart(URL).file(svg).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedImages() throws Exception {
        byte[] huge = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG_1PX, 0, huge, 0, PNG_1PX.length);
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", huge);

        mockMvc.perform(multipart(URL).file(file).header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isPayloadTooLarge());
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "a.png", "image/png", PNG_1PX);
    }
}
