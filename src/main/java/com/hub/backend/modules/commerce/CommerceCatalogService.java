package com.hub.backend.modules.commerce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.commerce.dto.CommerceCatalogResponse;
import com.hub.backend.modules.commerce.dto.CommerceCategoryResponse;
import com.hub.backend.modules.commerce.dto.CommerceToolResponse;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 电商工具目录：按分类把启用的工具分好组直接给前端，前端不再持有工具清单。
 */
@Service
@Transactional(readOnly = true)
public class CommerceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CommerceCatalogService.class);

    private static final TypeReference<Map<String, CommerceToolResponse.UploadMeta>> UPLOAD_ROLES_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> RETOUCH_MODES_TYPE = new TypeReference<>() {
            };
    private static final TypeReference<Map<String, Object>> FORM_SCHEMA_TYPE =
            new TypeReference<>() {
            };

    private final CommerceToolRepository tools;
    private final ObjectMapper objectMapper;

    public CommerceCatalogService(CommerceToolRepository tools, ObjectMapper objectMapper) {
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    /**
     * 某个场景的目录：电商工具（/e-commerce）与绘画工具（/painting 工具库）共用这张表，
     * 靠 {@code scene} 分开。
     */
    public CommerceCatalogResponse catalog(Long siteId, String scene) {
        List<CommerceToolEntity> rows = tools
                .findBySiteIdAndSceneAndStatusOrderBySortOrderAscIdAsc(
                        siteId, scene, CommerceToolEntity.STATUS_ACTIVE);

        // LinkedHashMap：沿用 sort_order 的先后，分类顺序即配置顺序
        Map<String, List<CommerceToolResponse>> grouped = new LinkedHashMap<>();
        for (CommerceToolEntity row : rows) {
            grouped.computeIfAbsent(row.getCategory(), key -> new ArrayList<>()).add(toResponse(row));
        }

        List<CommerceCategoryResponse> categories = grouped.entrySet().stream()
                .map(entry -> new CommerceCategoryResponse(entry.getKey(), entry.getValue()))
                .toList();
        return new CommerceCatalogResponse(categories);
    }

    /**
     * 单个工具。
     *
     * <p>先按站点找，找不到再看平台级工具（{@code site_id = null}）—— 派生查询里
     * {@code site_id = null} 永远匹配不到行，只写一次会出现「目录里有、单个取不到」。</p>
     */
    public CommerceToolResponse tool(Long siteId, String toolKey) {
        CommerceToolEntity row = tools
                .findFirstBySiteIdAndToolKeyAndStatus(siteId, toolKey, CommerceToolEntity.STATUS_ACTIVE)
                .or(() -> tools.findByToolKey(toolKey).stream()
                        .filter(item -> CommerceToolEntity.STATUS_ACTIVE.equalsIgnoreCase(item.getStatus()))
                        .filter(item -> item.getSiteId() == null
                                || Objects.equals(item.getSiteId(), siteId))
                        .findFirst())
                .orElseThrow(() -> new NotFoundException(
                        "Tool '%s' was not found".formatted(toolKey)));
        return toResponse(row);
    }

    private CommerceToolResponse toResponse(CommerceToolEntity row) {
        return new CommerceToolResponse(
                row.getToolKey(),
                row.getName(),
                row.getDescription(),
                row.getPrompt(),
                row.getForm(),
                row.getGlyph(),
                row.getIconUrl(),
                row.getAccent(),
                parse(row.getUploadRoles(), UPLOAD_ROLES_TYPE, row.getToolKey()),
                parse(row.getRetouchModes(), RETOUCH_MODES_TYPE, row.getToolKey()),
                parse(row.getFormSchema(), FORM_SCHEMA_TYPE, row.getToolKey()));
    }

    /** 单个工具的配置写坏了不应该让整个目录 500，退化成 null 由前端按表单形态兜底 */
    private <T> T parse(String json, TypeReference<T> type, String toolKey) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            log.warn("commerce tool {} has malformed config: {}", toolKey, ex.getMessage());
            return null;
        }
    }
}
