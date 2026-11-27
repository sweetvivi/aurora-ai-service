package com.hub.backend.modules.admin.commerce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.api.PageResponse;
import com.hub.backend.common.exception.ConflictException;
import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 电商工具的后台维护。
 *
 * <p>这一层只做一件事：把「表单 schema / 案例图 / 图标文案」这些本来靠 SQL 迁移写死的内容，
 * 变成运营能自己改的数据。前台目录接口（{@code /api/v1/commerce/catalog}）不需要任何改动，
 * 它读的仍是同一张表。</p>
 *
 * <p>两个刻意的限制：</p>
 * <ul>
 *   <li>{@code toolKey} 创建后不可改 —— 它是前台提交生成请求的标识，也是本地历史记录的关联键，
 *       改掉等于把老记录变成孤儿；需要新 key 请用「复制」。</li>
 *   <li>删除是软删除 —— 行留在库里（{@code status=DELETED}），前台不再下发，后台可随时恢复。</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CommerceToolAdminService {

    private static final Logger log = LoggerFactory.getLogger(CommerceToolAdminService.class);

    /** 与前端 {@code CommerceForm} 对齐；写错会让工作台渲染出空面板，所以在入口拦住 */
    private static final Set<String> FORMS =
            Set.of("background", "pattern", "placeholder", "replace", "retouch", "try-on");

    private static final Set<String> STATUSES =
            Set.of(CommerceToolEntity.STATUS_ACTIVE, CommerceToolEntity.STATUS_DELETED);

    private static final Pattern TOOL_KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,127}$");

    private static final int MAX_UPLOAD_ROLES_CHARS = 2048;
    private static final int MAX_RETOUCH_MODES_CHARS = 512;
    private static final int MAX_FORM_SCHEMA_CHARS = 4000;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final CommerceToolRepository tools;
    private final ObjectMapper objectMapper;

    public CommerceToolAdminService(CommerceToolRepository tools, ObjectMapper objectMapper) {
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    /* ------------------------------ 查询 ------------------------------ */

    public PageResponse<CommerceToolAdminResponse> list(Integer page,
                                                        Integer size,
                                                        Long siteId,
                                                        String scene,
                                                        String category,
                                                        String status,
                                                        String keyword,
                                                        String sort) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? 20 : Math.min(size, 200);

        Page<CommerceToolEntity> result = tools.findAll(
                filter(siteId, scene, category, status, keyword),
                PageRequest.of(pageIndex, pageSize, resolveSort(sort)));

        List<CommerceToolAdminResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        return PageResponse.of(items, result.getTotalElements(), pageIndex, pageSize);
    }

    public CommerceToolAdminResponse detail(Long id) {
        return toResponse(require(id));
    }

    /**
     * 分类下拉：按 sort_order 首次出现的顺序去重，含已删除的条目。
     *
     * <p>含已删除是必要的 —— 运营在「只看已删除」时仍要能按分类筛选，
     * 否则下架工具会变成找不到也恢复不了。</p>
     *
     * <p>{@code scene} 传了就只返回该场景的分类。管理端已把电商与绘画拆成两个页面，
     * 不按场景过滤的话，「绘画工具」页面的分类下拉会混进 15 个电商分类。</p>
     */
    public List<String> categories(Long siteId, String scene) {
        Sort sort = Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.ASC, "id"));
        String trimmedScene = trimToNull(scene);
        Specification<CommerceToolEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (siteId != null) {
                predicates.add(cb.equal(root.get("siteId"), siteId));
            }
            if (trimmedScene != null) {
                predicates.add(cb.equal(root.get("scene"), trimmedScene));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Set<String> unique = new LinkedHashSet<>();
        for (CommerceToolEntity row : tools.findAll(spec, sort)) {
            if (row.getCategory() != null) {
                unique.add(row.getCategory());
            }
        }
        return new ArrayList<>(unique);
    }

    /* ------------------------------ 写入 ------------------------------ */

    @Transactional
    public CommerceToolAdminResponse create(CommerceToolAdminRequest request) {
        String toolKey = trimToNull(request.toolKey());
        if (toolKey == null) {
            throw new InvalidRequestException("toolKey 不能为空");
        }
        if (!TOOL_KEY.matcher(toolKey).matches()) {
            throw new InvalidRequestException("toolKey 只能包含小写字母、数字和连字符，且不能以连字符开头");
        }
        if (existsKey(request.siteId(), toolKey)) {
            throw new ConflictException("已存在 toolKey 为 " + toolKey + " 的工具");
        }

        CommerceToolEntity entity = CommerceToolEntity.newTool();
        entity.setToolKey(toolKey);
        entity.setSiteId(request.siteId());
        entity.setScene(normalizeScene(request.scene()));
        entity.setCategory(requireText(request.category(), "category"));
        entity.setName(requireText(request.name(), "name"));
        entity.setForm(requireForm(request.form()));
        entity.setSortOrder(request.sortOrder() == null ? 0 : Math.max(0, request.sortOrder()));
        entity.setStatus(normalizeStatus(request.status()));

        applyOptional(entity, request);
        return toResponse(tools.save(entity));
    }

    @Transactional
    public CommerceToolAdminResponse update(Long id, CommerceToolAdminRequest request) {
        CommerceToolEntity entity = require(id);

        String incomingKey = trimToNull(request.toolKey());
        if (incomingKey != null && !incomingKey.equals(entity.getToolKey())) {
            throw new InvalidRequestException("toolKey 是稳定标识，创建后不可修改；需要新标识请使用「复制」");
        }

        if (request.siteId() != null && !Objects.equals(request.siteId(), entity.getSiteId())
                && existsKey(request.siteId(), entity.getToolKey())) {
            throw new ConflictException("目标站点下已存在同名 toolKey 的工具");
        }

        if (request.siteId() != null) {
            entity.setSiteId(request.siteId());
        }
        if (request.scene() != null) {
            entity.setScene(normalizeScene(request.scene()));
        }
        if (trimToNull(request.category()) != null) {
            entity.setCategory(request.category().trim());
        }
        if (trimToNull(request.name()) != null) {
            entity.setName(request.name().trim());
        }
        if (request.form() != null) {
            entity.setForm(requireForm(request.form()));
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(Math.max(0, request.sortOrder()));
        }
        if (request.status() != null) {
            entity.setStatus(normalizeStatus(request.status()));
        }

        applyOptional(entity, request);
        return toResponse(tools.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        CommerceToolEntity entity = require(id);
        entity.setStatus(CommerceToolEntity.STATUS_DELETED);
        tools.save(entity);
    }

    /**
     * 彻底删除（物理删行）。
     *
     * <p>要求先下架：这一步不可恢复，直接开放给「正常」状态的工具容易误点。
     * 先下架还有个实际好处 —— 前台立刻看不到它，运营有机会发现删错。</p>
     *
     * <p>历史关联：工具的 toolKey 会被写进前台本地历史与后端生成日志。目前这些都不做外键，
     * 删掉之后旧记录只是展示成一个找不到的 key，不会阻塞任何查询。</p>
     */
    @Transactional
    public void purge(Long id) {
        CommerceToolEntity entity = require(id);
        if (!CommerceToolEntity.STATUS_DELETED.equals(entity.getStatus())) {
            throw new InvalidRequestException("只能彻底删除已下架的工具，请先下架");
        }
        tools.delete(entity);
    }

    /**
     * 复制一份工具作为新草稿。
     *
     * <p>运营最常见的动作是「照着已有工具改一个」，复制把 prompt、表单 schema、案例图
     * 全部带过来，只把 key / 名称换成新的，省去重新描述 50 行提示词。</p>
     */
    @Transactional
    public CommerceToolAdminResponse duplicate(Long id) {
        CommerceToolEntity source = require(id);

        CommerceToolEntity copy = CommerceToolEntity.newTool();
        copy.setSiteId(source.getSiteId());
        copy.setScene(source.getScene());
        copy.setToolKey(nextAvailableKey(source.getSiteId(), source.getToolKey()));
        copy.setCategory(source.getCategory());
        copy.setName(clip(source.getName() + "（副本）", 255));
        copy.setDescription(source.getDescription());
        copy.setPrompt(source.getPrompt());
        copy.setForm(source.getForm());
        copy.setGlyph(source.getGlyph());
        copy.setIconUrl(source.getIconUrl());
        copy.setAccent(source.getAccent());
        copy.setUploadRoles(source.getUploadRoles());
        copy.setRetouchModes(source.getRetouchModes());
        copy.setFormSchema(source.getFormSchema());
        // 紧跟在原工具后面，避免副本跑到分类末尾
        copy.setSortOrder(source.getSortOrder() + 1);
        copy.setStatus(CommerceToolEntity.STATUS_ACTIVE);

        return toResponse(tools.save(copy));
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 处理「可清空」的字段。{@code null} 与空集合的区别在这里：null 保持原值，空集合写入 null。
     */
    private void applyOptional(CommerceToolEntity entity, CommerceToolAdminRequest request) {
        if (request.description() != null) {
            entity.setDescription(request.description().isBlank() ? null : request.description());
        }
        if (request.prompt() != null) {
            entity.setPrompt(request.prompt().isBlank() ? null : request.prompt());
        }
        if (request.glyph() != null) {
            entity.setGlyph(trimToNull(request.glyph()));
        }
        if (request.iconUrl() != null) {
            // 空字符串表示「回到文字图标」，所以这里不能退回 null 保持原值
            entity.setIconUrl(trimToNull(request.iconUrl()));
        }
        if (request.accent() != null) {
            entity.setAccent(trimToNull(request.accent()));
        }
        if (request.uploadRoles() != null) {
            entity.setUploadRoles(writeJson(request.uploadRoles(), MAX_UPLOAD_ROLES_CHARS, "uploadRoles"));
        }
        if (request.retouchModes() != null) {
            entity.setRetouchModes(writeJson(request.retouchModes(), MAX_RETOUCH_MODES_CHARS, "retouchModes"));
        }
        if (request.formSchema() != null) {
            entity.setFormSchema(writeFormSchema(request.formSchema()));
        }
    }

    /**
     * 表单 schema 会直接驱动前台控件，不能只保证它是可序列化 JSON；还要拦住
     * uploads/fields/gallery 写成字符串、字段缺 key 或未知控件类型等会导致降级渲染的配置。
     */
    private String writeFormSchema(Map<String, Object> value) {
        String json = writeJson(value, MAX_FORM_SCHEMA_CHARS, "formSchema");
        if (json == null) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                throw new InvalidRequestException("formSchema 顶层必须是对象");
            }
            for (String key : List.of("uploads", "fields", "gallery")) {
                JsonNode node = root.get(key);
                if (node != null && !node.isArray()) {
                    throw new InvalidRequestException("formSchema." + key + " 必须是数组");
                }
            }
            for (String key : List.of("guide", "credits")) {
                JsonNode node = root.get(key);
                if (node != null && !node.isObject()) {
                    throw new InvalidRequestException("formSchema." + key + " 必须是对象");
                }
            }
            validateSchemaItems(root.get("uploads"), "uploads", false);
            validateSchemaItems(root.get("fields"), "fields", true);
            validateSchemaItems(root.get("gallery"), "gallery", false);
            return json;
        } catch (InvalidRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidRequestException("formSchema 不是有效 JSON");
        }
    }

    private void validateSchemaItems(JsonNode items, String key, boolean fields) {
        if (items == null) return;
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            if (!item.isObject()) {
                throw new InvalidRequestException("formSchema." + key + "[" + index + "] 必须是对象");
            }
            if ("gallery".equals(key)) {
                JsonNode url = item.get("url");
                if (url != null && !url.isTextual()) {
                    throw new InvalidRequestException("formSchema.gallery[" + index + "].url 必须是字符串");
                }
                continue;
            }
            for (String required : fields ? List.of("key", "label", "type") : List.of("key", "label")) {
                if (!item.has(required) || !item.get(required).isTextual()) {
                    throw new InvalidRequestException("formSchema." + key + "[" + index + "] 缺少字符串字段 " + required);
                }
            }
            if (fields && !List.of("chips", "select", "text", "textarea").contains(item.get("type").asText())) {
                throw new InvalidRequestException("formSchema.fields[" + index + "].type 不是支持的控件类型");
            }
            if (fields && item.has("options") && !item.get("options").isArray()) {
                throw new InvalidRequestException("formSchema.fields[" + index + "].options 必须是数组");
            }
        }
    }

    /**
     * 序列化 JSON 列并卡长度。
     *
     * <p>这几列是 VARCHAR 而不是 LONGTEXT（见实体注释），超长会变成 MySQL 的截断或 500。
     * 在这里提前换成 400，顺带把超长提示成「哪一列超了多少」，比数据库报错好读。</p>
     */
    private String writeJson(Object value, int maxChars, String field) {
        if (isEmptyCollection(value)) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new InvalidRequestException(field + " 无法序列化为 JSON");
        }
        if (json.length() > maxChars) {
            throw new InvalidRequestException(
                    field + " 过长（" + json.length() + " 字符），上限 " + maxChars + " 字符");
        }
        return json;
    }

    private boolean isEmptyCollection(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private CommerceToolEntity require(Long id) {
        return tools.findById(id)
                .orElseThrow(() -> new NotFoundException("电商工具不存在：" + id));
    }

    private Specification<CommerceToolEntity> filter(Long siteId,
                                                     String scene,
                                                     String category,
                                                     String status,
                                                     String keyword) {
        String trimmedScene = trimToNull(scene);
        String trimmedCategory = trimToNull(category);
        String trimmedStatus = trimToNull(status);
        String trimmedKeyword = trimToNull(keyword);

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (siteId != null) {
                predicates.add(cb.equal(root.get("siteId"), siteId));
            }
            // 电商与绘画共用一张表：管理端不筛场景时两者会混排，
            // 运营在电商页翻到「老照片修复」会以为建错了地方
            if (trimmedScene != null) {
                predicates.add(cb.equal(root.get("scene"), trimmedScene));
            }
            if (trimmedCategory != null) {
                predicates.add(cb.equal(root.get("category"), trimmedCategory));
            }
            if (trimmedStatus != null) {
                predicates.add(cb.equal(root.get("status"), trimmedStatus));
            }
            if (trimmedKeyword != null) {
                String like = "%" + trimmedKeyword.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("toolKey")), like),
                        cb.like(cb.lower(root.get("category")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)));
            }
            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Sort resolveSort(String sort) {
        return switch (sort == null ? "" : sort) {
            case "updated" -> Sort.by(Sort.Direction.DESC, "updatedAt");
            case "created" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "name" -> Sort.by(Sort.Direction.ASC, "name");
            default -> Sort.by(Sort.Direction.ASC, "sortOrder")
                    .and(Sort.by(Sort.Direction.ASC, "id"));
        };
    }

    /** 同一站点下 toolKey 是否已被占用（含软删除行，避免恢复时撞唯一键） */
    private boolean existsKey(Long siteId, String toolKey) {
        return tools.findByToolKey(toolKey).stream()
                .anyMatch(row -> Objects.equals(row.getSiteId(), siteId));
    }

    private String nextAvailableKey(Long siteId, String baseKey) {
        String base = clip(baseKey, 100) + "-copy";
        String candidate = base;
        int index = 2;
        while (existsKey(siteId, candidate)) {
            candidate = base + "-" + index++;
        }
        return candidate;
    }

    private String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new InvalidRequestException(field + " 不能为空");
        }
        return trimmed;
    }

    private String requireForm(String form) {
        String trimmed = requireText(form, "form");
        if (!FORMS.contains(trimmed)) {
            throw new InvalidRequestException("form 只能是 " + String.join(" / ", FORMS.stream().sorted().toList()));
        }
        return trimmed;
    }

    private String normalizeStatus(String status) {
        String trimmed = trimToNull(status);
        if (trimmed == null) {
            return CommerceToolEntity.STATUS_ACTIVE;
        }
        String upper = trimmed.toUpperCase();
        if (!STATUSES.contains(upper)) {
            throw new InvalidRequestException("status 只能是 ACTIVE 或 DELETED");
        }
        return upper;
    }

    /**
     * 场景缺省为电商（老调用方都没传这个字段）。
     *
     * <p>非法值直接 400：写错场景名本该当场发现，静默退回电商会让人以为
     * 「工具建好了，只是绘画页看不到」。</p>
     */
    private String normalizeScene(String scene) {
        String trimmed = trimToNull(scene);
        if (trimmed == null) {
            return CommerceToolEntity.SCENE_COMMERCE;
        }
        String lower = trimmed.toLowerCase();
        if (!CommerceToolEntity.SCENE_COMMERCE.equals(lower)
                && !CommerceToolEntity.SCENE_PAINTING.equals(lower)) {
            throw new InvalidRequestException("scene 只能是 commerce 或 painting");
        }
        return lower;
    }

    private CommerceToolAdminResponse toResponse(CommerceToolEntity row) {
        return new CommerceToolAdminResponse(
                row.getId(),
                row.getSiteId(),
                row.getScene(),
                row.getToolKey(),
                row.getCategory(),
                row.getName(),
                row.getDescription(),
                row.getPrompt(),
                row.getForm(),
                row.getGlyph(),
                row.getIconUrl(),
                row.getAccent(),
                readMap(row.getUploadRoles(), row.getToolKey()),
                readStringList(row.getRetouchModes(), row.getToolKey()),
                readMap(row.getFormSchema(), row.getToolKey()),
                row.getSortOrder(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** 单条数据坏掉不应该让列表 500：解析失败记一条 warn，字段退化成 null */
    private Map<String, Object> readMap(String json, String toolKey) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            log.warn("commerce tool {} has malformed JSON column: {}", toolKey, ex.getMessage());
            return null;
        }
    }

    private List<String> readStringList(String json, String toolKey) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            log.warn("commerce tool {} has malformed JSON column: {}", toolKey, ex.getMessage());
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
