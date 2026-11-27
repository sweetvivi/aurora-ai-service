package com.hub.backend.modules.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.ppt.dto.PptDtos.TemplateDetailResponse;
import com.hub.backend.modules.ppt.dto.PptDtos.TemplateSummaryResponse;
import com.hub.backend.modules.ppt.entity.PptTemplateEntity;
import com.hub.backend.modules.ppt.repository.PptTemplateRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class PptTemplateService {

    private final ObjectMapper objectMapper;
    private final PptTemplateRepository customRepository;
    private List<TemplateDetailResponse> templates = List.of();

    public PptTemplateService(ObjectMapper objectMapper, PptTemplateRepository customRepository) {
        this.objectMapper = objectMapper;
        this.customRepository = customRepository;
    }

    @PostConstruct
    void load() throws IOException {
        ClassPathResource resource = new ClassPathResource("ppt/templates-catalog.json");
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<TemplateDetailResponse> loaded = new ArrayList<>();
            for (JsonNode node : root.path("templates")) {
                List<String> scenes = stringList(node.path("scenes"));
                List<String> layouts = stringList(node.path("layouts"));
                loaded.add(new TemplateDetailResponse(
                        text(node, "templateId"),
                        text(node, "name"),
                        text(node, "nameZh"),
                        text(node, "category"),
                        text(node, "description"),
                        text(node, "preview"),
                        text(node, "master"),
                        scenes,
                        layouts,
                        node.path("layoutCount").asInt(layouts.size())));
            }
            this.templates = List.copyOf(loaded);
        }
    }

    public List<TemplateSummaryResponse> list(String category, String scene) {
        List<TemplateDetailResponse> all = new ArrayList<>(templates);
        customRepository.findAllByStatusOrderByUpdatedAtDesc(PptTemplateEntity.PUBLISHED).forEach(e -> {
            if (all.stream().noneMatch(t -> t.templateId().equalsIgnoreCase(e.getTemplateId()))) {
                all.add(new TemplateDetailResponse(e.getTemplateId(), e.getName(), e.getName(), e.getCategory(), e.getDescription(), e.getPreviewUrl(), "ppt-master://" + e.getTemplateId(), parseJsonList(e.getScenesJson()), parseJsonList(e.getLayoutsJson()), parseJsonList(e.getLayoutsJson()).size()));
            }
        });
        return all.stream()
                .filter(t -> !StringUtils.hasText(category)
                        || category.equalsIgnoreCase(t.category())
                        || category.equalsIgnoreCase(t.nameZh()))
                .filter(t -> !StringUtils.hasText(scene)
                        || t.scenes().stream().anyMatch(s -> s.equalsIgnoreCase(scene)))
                .map(t -> new TemplateSummaryResponse(
                        t.templateId(),
                        t.name(),
                        t.nameZh(),
                        t.category(),
                        t.description(),
                        t.preview(),
                        t.scenes(),
                        t.layoutCount()))
                .collect(Collectors.toList());
    }

    public TemplateDetailResponse detail(String templateId) {
        TemplateDetailResponse builtIn = templates.stream()
                .filter(t -> t.templateId().equalsIgnoreCase(templateId))
                .findFirst().orElse(null);
        if (builtIn != null) return builtIn;
        return customRepository.findByTemplateIdIgnoreCase(templateId).filter(e -> PptTemplateEntity.PUBLISHED.equals(e.getStatus()))
                .map(e -> new TemplateDetailResponse(e.getTemplateId(), e.getName(), e.getName(), e.getCategory(), e.getDescription(), e.getPreviewUrl(), "ppt-master://" + e.getTemplateId(), parseJsonList(e.getScenesJson()), parseJsonList(e.getLayoutsJson()), parseJsonList(e.getLayoutsJson()).size()))
                .orElseThrow(() -> new NotFoundException("模板不存在: " + templateId));
    }

    public boolean exists(String templateId) {
        if (!StringUtils.hasText(templateId)) {
            return false;
        }
        String id = templateId.trim().toLowerCase(Locale.ROOT);
        return templates.stream().anyMatch(t -> t.templateId().equalsIgnoreCase(id)) || customRepository.findByTemplateIdIgnoreCase(id).map(e -> PptTemplateEntity.PUBLISHED.equals(e.getStatus())).orElse(false);
    }

    private List<String> parseJsonList(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        try { return objectMapper.readValue(raw, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception ignored) { return List.of(); }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && !item.isNull() && StringUtils.hasText(item.asText())) {
                    values.add(item.asText());
                }
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
