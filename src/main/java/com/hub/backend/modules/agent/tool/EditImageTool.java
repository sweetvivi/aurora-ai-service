package com.hub.backend.modules.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.common.exception.LlmInvocationException;
import com.hub.backend.common.exception.UpstreamFailureText;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.hub.backend.modules.file.entity.FileSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话改图：以会话焦点图（默认最新一张）为唯一参考图，按用户指令图生图。
 *
 * <p>参考图由服务端注入 {@link ToolContext#focusImages()}，不要求用户本轮再上传。</p>
 */
@Component
public class EditImageTool implements AgentTool {

    public static final String TOOL_NAME = "edit_image";

    private static final int MAX_INSTRUCTION = 4000;
    private static final Pattern FILE_ID_IN_URL = Pattern.compile("/files/(\\d+)/content", Pattern.CASE_INSENSITIVE);
    private static final Logger log = LoggerFactory.getLogger(EditImageTool.class);

    private final SmartEditImageService imageService;
    private final ObjectMapper objectMapper;

    public EditImageTool(SmartEditImageService imageService, ObjectMapper objectMapper) {
        this.imageService = imageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("instruction", Map.of(
                "type", "string",
                "description", "要改的地方，用用户的原话即可，例如「换成白天」「背景改成海边」「加一顶帽子」。"
                        + "只写改动点，不要把整张图的场景重写一遍，也不要顺带改用户没提到的部分。"
                        + "系统会自动带上会话里最新的那张图，无需用户再上传或指定。"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("instruction"));

        return new LlmToolDefinition(
                TOOL_NAME,
                "修改会话中已有的图片。用户说换背景、改风格、加/删元素、更清晰、基于上一张继续改时调用；"
                        + "全新主题请用 generate_image。",
                schema);
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public ToolOutcome execute(LlmToolCall call, ToolContext context) {
        String instruction;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            JsonNode args = objectMapper.readTree(raw);
            instruction = args.path("instruction").asText("");
            if (instruction.isBlank()) {
                // Some models reuse "prompt" — accept either.
                instruction = args.path("prompt").asText("");
            }
        } catch (JsonProcessingException malformed) {
            return ToolOutcome.failed("工具参数不是合法 JSON：" + malformed.getOriginalMessage());
        }

        if (instruction.isBlank()) {
            return ToolOutcome.failed("缺少 instruction 参数，请说明要如何修改图片");
        }
        if (instruction.length() > MAX_INSTRUCTION) {
            instruction = instruction.substring(0, MAX_INSTRUCTION);
        }

        List<LlmImageAttachment> references = context.referenceImagesForEdit();
        if (references.isEmpty()) {
            return ToolOutcome.failed("当前会话没有可修改的图片。请先生成一张图，或让用户点「以此图继续」。");
        }

        String source = context.fileSource() == null
                ? FileSource.IMAGE_GENERATION.value()
                : context.fileSource();
        FileSource fileSource = FileSource.fromValueOrNull(source);
        if (fileSource == null) {
            fileSource = FileSource.IMAGE_GENERATION;
        }

        MultipartFile image = new InlineImage(references.get(0), 0);
        try {
            ImageUrlResponse result = imageService.compose(
                    context.currentUser(),
                    List.of(image),
                    instruction,
                    context.imageModel(),
                    null,
                    fileSource,
                    context.conversationId());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", result.url());
            Long fileId = fileIdFromUrl(result.url());
            if (fileId != null) {
                item.put("fileId", fileId);
            }
            List<Map<String, Object>> images = List.of(item);
            log.info("edit_image produced 1 image for user {}", context.currentUser().userId());
            return ToolOutcome.ok(
                    objectMapper.writeValueAsString(Map.of("images", images)),
                    Map.of("images", images));
        } catch (Exception failure) {
            log.warn("edit_image failed: {}", failure.getMessage());
            String upstreamCode = failure instanceof LlmInvocationException llm ? llm.getUpstreamCode() : null;
            // 与 generate_image 同一口径：不提服务商 / 网关，模型转述时也不会泄漏内部链路。
            return ToolOutcome.failed("这次没有修改出图片：" + UpstreamFailureText.describe(
                    failure.getMessage(), upstreamCode, "请稍后再试"));
        }
    }

    static Long fileIdFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher matcher = FILE_ID_IN_URL.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class InlineImage implements MultipartFile {

        private final byte[] content;
        private final String fieldName;
        private final String filename;
        private final String contentType;

        private InlineImage(LlmImageAttachment attachment, int index) {
            this.content = attachment.content();
            this.contentType = attachment.mimeType();
            this.fieldName = "image" + (index + 1);
            this.filename = "focus-" + (index + 1) + extensionOf(attachment.mimeType());
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
