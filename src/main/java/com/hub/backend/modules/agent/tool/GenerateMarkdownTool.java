package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.dto.UploadedFileResponse;
import com.hub.backend.modules.file.entity.FileAssetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a downloadable Markdown file for the current user.
 *
 * <p>Chat injects this tool on the stream path; agents must enable ability
 * {@code generate_markdown} explicitly ({@link #enabledByDefault()} is false).</p>
 */
@Component
public class GenerateMarkdownTool implements AgentTool {

    public static final String TOOL_NAME = "generate_markdown";

    private static final String MIME = "text/markdown";
    private static final int MAX_CONTENT_CHARS = 500_000;
    private static final Logger log = LoggerFactory.getLogger(GenerateMarkdownTool.class);

    private final FileService fileService;
    private final ObjectMapper objectMapper;

    public GenerateMarkdownTool(FileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of(
                "type", "string",
                "description", "文档标题；未写 filename 时用于生成可读文件名"));
        properties.put("filename", Map.of(
                "type", "string",
                "description", "文件名，可省略 .md 扩展名；未写时依次使用 title、正文首个 Markdown 标题"));
        properties.put("content", Map.of(
                "type", "string",
                "description", "完整 Markdown 正文"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("content"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "生成可下载的 Markdown（.md）文件。用户要求 md、Markdown、纯文本说明文档时调用；"
                        + "若用户要 Word/docx/可编辑正式文档，请改用 generate_docx，不要用本工具。"
                        + "把完整正文放进 content，不要只贴在聊天里。",
                schema);
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        String filename;
        String title;
        String content;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            JsonNode args = objectMapper.readTree(raw);
            filename = args.path("filename").asText("").trim();
            title = args.path("title").asText("").trim();
            content = args.path("content").asText("");
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed("工具参数不是合法 JSON：" + malformed.getOriginalMessage());
        }

        if (content.isBlank()) {
            return ToolOutcome.failed("缺少 content 参数，请给出 Markdown 正文");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return ToolOutcome.failed("Markdown 正文过长，请控制在 " + MAX_CONTENT_CHARS + " 字符以内");
        }

        if (filename.isBlank()) {
            filename = !title.isBlank() ? title : firstMarkdownHeading(content);
        }
        String safeName = sanitizeFilename(filename, "document.md", "md");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        CurrentUser user = context.currentUser();

        try {
            UploadedFileResponse stored = fileService.storeGenerated(
                    user, bytes, "md", MIME, safeName,
                    FileAssetType.GENERATED, context.conversationId());
            Map<String, Object> fileMeta = GeneratedFileMeta.of(stored);
            String forModel = "已生成 Markdown 文件「" + stored.originalName() + "」。"
                    + "请用一两句话告知用户通过下方下载卡片下载；"
                    + "不要输出 Markdown 链接或文件 URL（系统会自动展示下载卡片），不要重复粘贴全文。";
            log.info("generate_markdown fileId={} user={}", stored.id(), user.userId());
            return ToolOutcome.ok(forModel, Map.of("files", List.of(fileMeta)));
        } catch (Exception failure) {
            log.warn("generate_markdown failed: {}", failure.getMessage());
            return ToolOutcome.failed("生成 Markdown 失败，请稍后重试");
        }
    }

    static String sanitizeFilename(String raw, String fallback, String extension) {
        String name = raw == null ? "" : raw.trim();
        if (name.isBlank()) {
            name = fallback;
        }
        name = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").strip();
        name = name.replaceAll("[. ]+$", "");
        if (name.isBlank()) {
            name = fallback;
        }
        String lower = name.toLowerCase();
        String ext = "." + extension.toLowerCase();
        if (!lower.endsWith(ext)) {
            name = name + ext;
        }
        if (name.length() > 180) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String suffix = dot > 0 ? name.substring(dot) : ext;
            stem = stem.substring(0, Math.min(stem.length(), 180 - suffix.length()));
            name = stem + suffix;
        }
        return name;
    }

    static String firstMarkdownHeading(String content) {
        if (content == null) {
            return "";
        }
        for (String line : content.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.matches("^#{1,6}\\s+.+$")) {
                return trimmed.replaceFirst("^#{1,6}\\s+", "").strip();
            }
        }
        return "";
    }
}
