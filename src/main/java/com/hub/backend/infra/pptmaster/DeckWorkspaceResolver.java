package com.hub.backend.infra.pptmaster;

import com.hub.backend.common.config.properties.PptProperties;
import com.hub.backend.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves official Deck workspace paths under ppt-master.
 */
@Component
public class DeckWorkspaceResolver {

    private final PptProperties pptProperties;

    public DeckWorkspaceResolver(PptProperties pptProperties) {
        this.pptProperties = pptProperties;
    }

    public Path resolve(String deckTemplateId) {
        String id = deckTemplateId == null ? "" : deckTemplateId.trim();
        if (!StringUtils.hasText(id)) {
            throw new InvalidRequestException("deckTemplateId 为空");
        }
        PptProperties.PptMaster cfg = pptProperties.getPptMaster();
        String root = cfg.getDeckTemplatesRoot();
        Path deckRoot;
        if (StringUtils.hasText(root)) {
            deckRoot = Path.of(root.trim()).toAbsolutePath().normalize();
        } else {
            String repo = cfg.getRepoRoot() == null ? "" : cfg.getRepoRoot().trim();
            if (!StringUtils.hasText(repo)) {
                throw new InvalidRequestException("ppt-master repo 未配置，无法定位 Deck");
            }
            deckRoot = Path.of(repo).toAbsolutePath().normalize()
                    .resolve("skills").resolve("ppt-master").resolve("templates").resolve("decks");
        }
        Path workspace = deckRoot.resolve(id);
        Path spec = workspace.resolve("templates").resolve("design_spec.md");
        if (!Files.isRegularFile(spec)) {
            throw new InvalidRequestException("Deck 不存在或缺少 design_spec：" + workspace);
        }
        return workspace.toAbsolutePath().normalize();
    }
}
