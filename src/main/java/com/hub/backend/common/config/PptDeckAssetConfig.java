package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.PptProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 把 ppt-master Deck 目录挂到 {@code /ppt-deck-assets/**}，供模板库封面等静态访问。
 *
 * <p>优先 {@code app.ppt.ppt-master.deck-templates-root}；否则
 * {@code {repo-root}/skills/ppt-master/templates/decks}。</p>
 */
@Configuration
public class PptDeckAssetConfig implements WebMvcConfigurer {

    private final PptProperties pptProperties;

    public PptDeckAssetConfig(PptProperties pptProperties) {
        this.pptProperties = pptProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path deckRoot = resolveDeckRoot();
        if (deckRoot == null || !Files.isDirectory(deckRoot)) {
            return;
        }
        String location = deckRoot.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/ppt-deck-assets/**")
                .addResourceLocations(location)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }

    private Path resolveDeckRoot() {
        PptProperties.PptMaster cfg = pptProperties.getPptMaster();
        String root = cfg.getDeckTemplatesRoot();
        if (StringUtils.hasText(root)) {
            return Path.of(root.trim()).toAbsolutePath().normalize();
        }
        String repo = cfg.getRepoRoot();
        if (!StringUtils.hasText(repo)) {
            return null;
        }
        return Path.of(repo.trim()).toAbsolutePath().normalize()
                .resolve("skills")
                .resolve("ppt-master")
                .resolve("templates")
                .resolve("decks");
    }
}
