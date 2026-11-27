package com.hub.backend.modules.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.agent.tool.AgentTool;
import com.hub.backend.modules.agent.tool.ToolContext;
import com.hub.backend.modules.commerce.entity.CommerceToolEntity;
import com.hub.backend.modules.commerce.repository.CommerceToolRepository;
import com.hub.backend.modules.smartedit.SmartEditImageService;
import com.hub.backend.modules.smartedit.dto.ImageUrlResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 让智能体直接调度电商工具箱里的 56 个工具。
 *
 * <p><b>为什么是一个工具而不是 56 个：</b>工具定义会随每一轮请求重发，56 个定义会把上下文
 * 撑成几万字符，模型在长清单里挑工具的准确率反而下降。这里只暴露一个
 * {@code run_commerce_tool(toolKey, prompt, params)}，工具清单写进描述里，模型先把需求
 * 收敛到一个 toolKey，再填提示词。</p>
 *
 * <p><b>提示词不再经过对话模型润色：</b>电商工具页那条链路是「用户表单 → 对话模型润色 →
 * 图像模型」，而智能体本身就是那个润色层 —— 再套一层等于把意图翻译两遍。所以这里直接复用
 * {@link CommerceComposeChatService#fallbackPrompt} 的拼装逻辑（基准提示词 + 参数 +
 * 「第几张图是什么」），跳过中间那次模型调用。</p>
 *
 * <p>参考图来自 {@link ToolContext#images()}：用户这一轮上传的图，按上传顺序对应工具
 * {@code form_schema.uploads} 里声明的槽位。没有图就直接失败并提示模型让用户补图，绝不
 * 靠「凭空生成」蒙混过去 —— 换背景、试穿这类需求没有原图是没有意义的。</p>
 */
@Component
public class CommerceAgentTool implements AgentTool {

    public static final String TOOL_NAME = "run_commerce_tool";

    /** 清单里每个工具的描述截断长度，控制把提示词撑大的边际收益 */
    private static final int CATALOG_NOTE_LIMIT = 24;

    /** 与图像接口一致的三档尺寸；发了别的值就当没传 */
    private static final Set<String> SIZES = Set.of("1024x1024", "1024x1536", "1536x1024");

    private static final Logger log = LoggerFactory.getLogger(CommerceAgentTool.class);

    private final CommerceToolRepository tools;
    private final SmartEditImageService imageService;
    private final CommerceComposeChatService composePrompts;
    private final ObjectMapper objectMapper;

    public CommerceAgentTool(CommerceToolRepository tools,
                             SmartEditImageService imageService,
                             CommerceComposeChatService composePrompts,
                             ObjectMapper objectMapper) {
        this.tools = tools;
        this.imageService = imageService;
        this.composePrompts = composePrompts;
        this.objectMapper = objectMapper;
    }

    /**
     * 业务能力，默认不开放。
     *
     * <p>这个工具的描述里带着整份电商工具清单（56 条、几千字符），而工具定义每轮都随请求
     * 重发。默认全开的话，平台上每个角色智能体（写代码的、聊电影的）都会白背一份
     * 「换背景 / 换模特 / 换材质」。它属于「多个工具或智能体联动」那一格，由运营在管理端
     * 显式加 {@code run_commerce_tool} 能力后才对指定智能体可见。</p>
     */
    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public LlmToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("toolKey", Map.of(
                "type", "string",
                "description", "要执行的电商工具标识，只能取下面清单里出现的 toolKey"));
        properties.put("prompt", Map.of(
                "type", "string",
                "description",
                "交给图像模型的最终中文提示词：主体、场景、光线、风格，以及哪些部分必须保持不变。"
                        + "指代参考图时必须用「第一张图 / 第二张图」，并说明把哪张图的什么应用到哪张图上"));
        properties.put("params", Map.of(
                "type", "object",
                "description", "工具参数，键为参数名、值为选项，例如 {\"输出比例\":\"1:1\"}；没有可省略"));
        properties.put("size", Map.of(
                "type", "string",
                "enum", List.of("1024x1024", "1024x1536", "1536x1024"),
                "description", "输出尺寸，默认 1024x1024"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("toolKey", "prompt"));

        return new LlmToolDefinition(TOOL_NAME, description(), schema);
    }

    /**
     * 工具描述 = 使用规则 + 可用工具清单。
     *
     * <p>清单每次都从库里读，运营在后台新建/下架工具后，下一轮对话就能看到，不需要重启。
     * 读失败只影响清单，不能让整个注册表在启动时就崩掉。</p>
     */
    private String description() {
        String catalog = catalog();
        return """
                执行一个电商图片工具：用户上传参考图，工具按固定业务语义出图（换背景、AI 试衣、换模特、\
                换材质、批量套图、场景迁移等）。

                什么时候调用：用户想「把这张图做成某种成品图」「换成 X 效果」「按这个模板出一套图」时。
                什么时候不要调用：用户只是想让你描述图片内容、写文案、或者凭空创作一张新图。

                可用工具（toolKey = 名称：说明）：
                %s

                用法：
                - toolKey 必填，且必须是上面清单中的值；
                - prompt 直接写最终提示词，不要反问用户，不要输出代码块；
                - 参考图由用户随消息上传，执行时按上传顺序传入，prompt 里必须用「第一张图 / 第二张图」指代。
                """.formatted(catalog.isBlank() ? "（当前没有启用的电商工具）" : catalog);
    }

    private String catalog() {
        List<CommerceToolEntity> rows;
        try {
            // 只列电商场景：绘画工具是 /painting 页面内的表单，不该出现在对话工具清单里。
            // 这份清单每轮都随请求发一遍，多几十条就是纯 token 开销。
            rows = tools.findBySceneAndStatusOrderBySortOrderAscIdAsc(
                    CommerceToolEntity.SCENE_COMMERCE, CommerceToolEntity.STATUS_ACTIVE);
        } catch (RuntimeException unavailable) {
            log.warn("Commerce tool catalog is unavailable: {}", unavailable.getMessage());
            return "";
        }
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        // 已按 sort_order 排好，分类在种子里就是连续的；顺序按配置走，不在这里重排
        String currentCategory = null;
        for (CommerceToolEntity row : rows) {
            String category = row.getCategory() == null ? "其他" : row.getCategory();
            if (!category.equals(currentCategory)) {
                text.append('[').append(category).append("]\n");
                currentCategory = category;
            }
            text.append("- ").append(row.getToolKey()).append(" = ").append(row.getName());
            String note = noteOf(row);
            if (!note.isBlank()) {
                text.append("：").append(note);
            }
            text.append('\n');
        }
        return text.toString().strip();
    }

    /** 简介截断到一行，避免 56 条描述把工具定义撑成几千 token */
    private static String noteOf(CommerceToolEntity row) {
        String raw = row.getDescription() == null ? "" : row.getDescription().strip()
                .replaceAll("\\s+", " ");
        if (raw.length() <= CATALOG_NOTE_LIMIT) {
            return raw;
        }
        return raw.substring(0, CATALOG_NOTE_LIMIT) + "…";
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        JsonNode args;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            args = objectMapper.readTree(raw);
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed("工具参数不是合法 JSON：" + malformed.getOriginalMessage());
        }

        String toolKey = args.path("toolKey").asText("").strip();
        if (toolKey.isBlank()) {
            return ToolOutcome.failed("缺少 toolKey 参数，请从工具清单里选一个");
        }
        if (!context.hasImages()) {
            return ToolOutcome.failed("本轮没有参考图。请先让用户上传 1-3 张图片，再执行「" + toolKey + "」");
        }

        Long siteId = context.currentUser().siteId();
        CommerceToolEntity tool = findTool(siteId, toolKey).orElse(null);
        if (tool == null) {
            return ToolOutcome.failed("没有找到工具 '%s'，请从工具清单里重新选择".formatted(toolKey));
        }

        String prompt = args.path("prompt").asText("");
        String paramsJson = paramsJson(args.path("params"));
        // 复用电商工具页的兜底拼装：基准提示词 + 参数 + 「第 N 张图是什么」
        String finalPrompt = composePrompts.fallbackPrompt(tool, prompt, paramsJson);

        List<MultipartFile> images = new ArrayList<>(context.images().size());
        for (int index = 0; index < context.images().size(); index++) {
            images.add(new InlineImage(context.images().get(index), index));
        }

        try {
            ImageUrlResponse result = imageService.compose(
                    context.currentUser(), images, finalPrompt, null, sizeOf(args));
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("url", result.url());
            image.put("toolKey", tool.getToolKey());
            image.put("toolName", tool.getName());
            List<Map<String, Object>> imagesMeta = List.of(image);
            log.info("run_commerce_tool '{}' produced {} image(s) for user {}",
                    tool.getToolKey(), 1, context.currentUser().userId());
            return ToolOutcome.ok(
                    "已用「%s」生成图片，已通过界面展示给用户。".formatted(tool.getName()),
                    Map.of("images", imagesMeta));
        } catch (RuntimeException failure) {
            log.warn("run_commerce_tool '{}' failed: {}", tool.getToolKey(), failure.getMessage());
            return ToolOutcome.failed("这次没有生成出图片："
                    + UpstreamFailureText.describe(
                            failure.getMessage(), null, "请稍后再试"));
        }
    }

    /**
     * 优先按站点查，查不到再看全局工具（{@code site_id = null}）。
     *
     * <p>不能只写一次派生查询：{@code site_id = null} 在 SQL 里永远匹配不到行，平台级工具
     * 会变成「清单里有、执行时找不到」。</p>
     */
    private Optional<CommerceToolEntity> findTool(Long siteId, String toolKey) {
        var scoped = tools.findFirstBySiteIdAndToolKeyAndStatus(
                siteId, toolKey, CommerceToolEntity.STATUS_ACTIVE);
        if (scoped.isPresent()) {
            return scoped;
        }
        return tools.findByToolKey(toolKey).stream()
                .filter(row -> CommerceToolEntity.STATUS_ACTIVE.equalsIgnoreCase(row.getStatus()))
                .filter(row -> row.getSiteId() == null || Objects.equals(row.getSiteId(), siteId))
                .findFirst();
    }

    private String sizeOf(JsonNode args) {
        String size = args.path("size").asText("").strip();
        return SIZES.contains(size) ? size : null;
    }

    /**
     * 把模型给的 {@code params} 摊成 {@code [{"label","value"}]}。
     *
     * <p>电商工具页提交的是数组，模型更自然地会给对象（{@code {"比例":"1:1"}}），这里两种都收。
     * 解析不了就当没传 —— 参数只是辅助信息，不该因为它让整次生成失败。</p>
     */
    private String paramsJson(JsonNode params) {
        if (params == null || params.isMissingNode() || params.isNull()) {
            return null;
        }
        if (params.isArray()) {
            return params.toString();
        }
        if (!params.isObject()) {
            return null;
        }
        ArrayNode array = objectMapper.createArrayNode();
        for (var fields = params.fields(); fields.hasNext(); ) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            String text = value.isValueNode() ? value.asText("") : value.toString();
            if (text.isBlank()) {
                continue;
            }
            ObjectNode item = array.addObject();
            item.put("label", entry.getKey());
            item.put("value", text);
        }
        return array.isEmpty() ? null : array.toString();
    }

    /**
     * 把已经读进内存的图片包装成 {@link MultipartFile}。
     *
     * <p>图像接口只接受上传文件（它按 name/contentType 做类型与扩展名校验），而智能体这边的图
     * 是从附件表读出来的字节，所以补一层内存实现，不去磁盘上再落一份临时文件。</p>
     */
    private static final class InlineImage implements MultipartFile {

        private final byte[] content;
        private final String fieldName;
        private final String filename;
        private final String contentType;

        private InlineImage(LlmImageAttachment attachment, int index) {
            this.content = attachment.content();
            this.contentType = attachment.mimeType();
            this.fieldName = "image" + (index + 1);
            this.filename = "reference-" + (index + 1) + extensionOf(attachment.mimeType());
        }

        private static String extensionOf(String mime) {
            String normalized = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "image/jpeg", "image/jpg" -> ".jpg";
                case "image/webp" -> ".webp";
                default -> ".png";
            };
        }

        @Override
        public String getName() {
            return fieldName;
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(Path.of(dest.toURI()), content);
        }
    }
}
