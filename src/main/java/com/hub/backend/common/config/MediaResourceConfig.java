package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.StorageProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 把公开素材目录挂到 {@code /media/**}。
 *
 * <p>只映射 {@code app.storage.public-root}（工具图标、案例图），<b>不映射</b> {@code local-root}。
 * 后者是用户附件目录，只能经带鉴权的接口读取；把它暴露成静态目录等于让所有附件变成
 * 「知道 URL 就能下载」。</p>
 *
 * <p>缓存一年：文件名是 UUID，内容永不变更，换图必然换 URL。</p>
 */
@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {

    private final StorageProperties properties;

    public MediaResourceConfig(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Path.of(properties.getPublicRoot()).toAbsolutePath().normalize();
        String location = root.toUri().toString();
        if (!location.endsWith("/")) {
            // Path.toUri() 对尚不存在的目录不会补结尾斜杠，缺了它会被当成文件而不是目录
            location = location + "/";
        }
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }
}
