package com.hub.backend.modules.agent;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.infra.llm.dto.LlmImageAttachment;
import com.hub.backend.infra.llm.AudioTranscriptionClient;
import com.hub.backend.infra.llm.ProviderCredentials;
import com.hub.backend.modules.agent.dto.ChatAttachmentRef;
import com.hub.backend.modules.auth.dto.CurrentUser;
import com.hub.backend.modules.file.DocumentTextExtractor;
import com.hub.backend.modules.file.DownloadedFile;
import com.hub.backend.modules.file.FileService;
import com.hub.backend.modules.file.entity.UploadedFileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把附件 ID 变成对话模型能吃的输入：图片走多模态，文本/办公文档展开进正文，音频交给转写模型。
 *
 * <p>两种调用场景刻意分开：</p>
 * <ul>
 *   <li>新消息（{@link #loadForNewMessage}）：用户刚选的附件，任何问题都要立刻报错，
 *       否则用户会看到一条「收到」但模型其实没看到内容。</li>
 *   <li>重建历史（{@link #loadForHistory}）：容错，文件被删掉或类型不对就跳过 ——
 *       不能因为一张旧图打不开，让整段对话都读不出来。</li>
 * </ul>
 */
@Component
public class ChatAttachmentLoader {

    /**
     * 单条消息最多几个附件。
     *
     * <p>五份覆盖「几张参考图 + 一两份文本材料」；再多会让模型注意力分散，
     * 也会让这一轮请求的体积和费用成倍上涨。</p>
     */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;

    /** @deprecated 使用 {@link #MAX_ATTACHMENTS_PER_MESSAGE} */
    public static final int MAX_IMAGES_PER_MESSAGE = MAX_ATTACHMENTS_PER_MESSAGE;

    /**
     * 单张图片送进模型的字节上限。
     *
     * <p>比附件上传上限低得多：上游反正会缩放到固定尺寸，传超大图只是浪费带宽
     * 与内存；超过这个数说明用户没压缩，直接告诉他自己压。</p>
     */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 25L * 1024 * 1024;

    /** 单个文本附件展开进 prompt 的字符上限 */
    private static final int MAX_FILE_TEXT_CHARS = 20_000;

    /** 本轮所有文本附件合计字符上限 */
    private static final int MAX_TOTAL_TEXT_CHARS = 40_000;

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json");

    /**
     * 模型对话默认可分析的附件类型，与前台 Composer 白名单对齐。
     *
     * <p>智能体仍按各自 UI {@code accept} 配置收窄；未显式传 acceptedTypes 时走本集合，
     * 避免对话页能上传 docx/xlsx 却在展开正文时被「仅图片」拒掉。</p>
     */
    public static final Set<String> DEFAULT_CHAT_ACCEPTED_TYPES = Set.of(
            "image/*",
            ".txt", ".md", ".csv", ".json",
            ".pdf", ".docx", ".xlsx", ".pptx",
            // 旧版二进制进库后由 DocumentTextExtractor 给出「请另存为」提示
            ".doc", ".xls", ".ppt",
            "text/plain", "text/markdown", "text/csv", "application/json", "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentLoader.class);

    private final FileService files;
    private final DocumentTextExtractor documentTextExtractor;
    private final AudioTranscriptionClient audioTranscription;

    public ChatAttachmentLoader(FileService files, DocumentTextExtractor documentTextExtractor,
                                AudioTranscriptionClient audioTranscription) {
        this.files = files;
        this.documentTextExtractor = documentTextExtractor;
        this.audioTranscription = audioTranscription;
    }

    public Loaded loadForNewMessage(CurrentUser currentUser, List<Long> attachmentIds) {
        return loadForNewMessage(currentUser, attachmentIds, null);
    }

    /**
     * 只解析附件元数据引用，不读二进制。
     *
     * <p>供落库使用：即使后续展开正文 / 读图失败，历史里仍能显示文件卡片与缩略图地址。</p>
     */
    public List<ChatAttachmentRef> refsFor(CurrentUser currentUser, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        if (attachmentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new InvalidRequestException(
                    "一条消息最多携带 %d 个附件".formatted(MAX_ATTACHMENTS_PER_MESSAGE));
        }
        List<ChatAttachmentRef> refs = new ArrayList<>(attachmentIds.size());
        for (Long id : attachmentIds) {
            refs.add(ChatAttachmentRef.of(files.requireOwned(id, currentUser)));
        }
        return List.copyOf(refs);
    }

    /**
     * 本轮新消息携带的附件：数量、归属、类型、体积都严格校验。
     *
     * <p>未指定 accept 时使用 {@link #DEFAULT_CHAT_ACCEPTED_TYPES}
     *（图片 + 文本 + pdf/docx/xlsx/pptx）。</p>
     */
    public Loaded loadForNewMessage(CurrentUser currentUser, List<Long> attachmentIds,
                                    ProviderCredentials credentials) {
        return loadForNewMessage(currentUser, attachmentIds, credentials, DEFAULT_CHAT_ACCEPTED_TYPES);
    }

    /** 新消息附件必须匹配传入的 accept 类型（智能体按 UI 配置收窄）。 */
    public Loaded loadForNewMessage(CurrentUser currentUser, List<Long> attachmentIds,
                                    ProviderCredentials credentials, Set<String> acceptedTypes) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return Loaded.empty();
        }
        if (attachmentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new InvalidRequestException(
                    "一条消息最多携带 %d 个附件".formatted(MAX_ATTACHMENTS_PER_MESSAGE));
        }
        List<LlmImageAttachment> images = new ArrayList<>(attachmentIds.size());
        List<ChatAttachmentRef> refs = new ArrayList<>(attachmentIds.size());
        List<String> transcripts = new ArrayList<>();
        StringBuilder documents = new StringBuilder();
        int textBudget = MAX_TOTAL_TEXT_CHARS;
        Set<String> normalizedAcceptedTypes = normalizeAcceptedTypes(acceptedTypes);
        for (Long id : attachmentIds) {
            DownloadedFile downloaded = files.download(id, currentUser);
            UploadedFileEntity row = downloaded.file();
            if (!isAccepted(row, normalizedAcceptedTypes)) {
                throw new InvalidRequestException(
                        "附件 '%s' 的文件类型不受支持，请上传图片，或 txt / md / csv / json / pdf / docx / xlsx / pptx"
                                .formatted(row.getOriginalName()));
            }
            refs.add(ChatAttachmentRef.of(row));
            if (isImageMime(row.getMimeType())) {
                requireImageSize(row);
                images.add(readImage(row, downloaded.resource()));
                continue;
            }
            if (isAudioMime(row.getMimeType())) {
                if (row.getSizeBytes() > MAX_AUDIO_BYTES) {
                    throw new InvalidRequestException("音频文件超过 25MB 上限");
                }
                transcripts.add(transcribe(row, downloaded.resource(), credentials));
                continue;
            }
            String extracted = readTextDocument(row, downloaded.resource(), textBudget);
            if (extracted != null && !extracted.isBlank()) {
                if (!documents.isEmpty()) {
                    documents.append("\n\n");
                }
                documents.append(extracted);
                textBudget = Math.max(0, textBudget - extracted.length());
            }
        }
        return new Loaded(images, refs, documents.toString(), String.join("\n", transcripts));
    }

    private static Set<String> normalizeAcceptedTypes(Set<String> acceptedTypes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (acceptedTypes != null) {
            for (String value : acceptedTypes) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (normalized.isEmpty()) {
            normalized.addAll(DEFAULT_CHAT_ACCEPTED_TYPES);
        }
        return normalized;
    }

    private static boolean isAccepted(UploadedFileEntity row, Set<String> acceptedTypes) {
        String mime = row.getMimeType() == null ? "" : row.getMimeType()
                .split(";")[0].strip().toLowerCase(Locale.ROOT);
        String extension = row.getExtension() == null ? "" : row.getExtension()
                .strip().toLowerCase(Locale.ROOT);
        for (String accepted : acceptedTypes) {
            if (accepted.startsWith(".")) {
                if (extension.equals(accepted.substring(1))) return true;
            } else if (accepted.endsWith("/*")) {
                if (mime.startsWith(accepted.substring(0, accepted.length() - 1))) return true;
            } else if (accepted.contains("/")) {
                if (mime.equals(accepted)) return true;
            } else if (extension.equals(accepted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重建历史时的图片：读不到就跳过。
     *
     * <p>传的是落库时的引用而不是 ID，省掉一次元数据查询；但文件可能已被用户删除，
     * 所以要吞掉 {@code NotFoundException}。</p>
     */
    public List<LlmImageAttachment> loadForHistory(CurrentUser currentUser, List<ChatAttachmentRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<LlmImageAttachment> images = new ArrayList<>(refs.size());
        for (ChatAttachmentRef ref : refs) {
            if (ref.id() == null || !isImageMime(ref.mimeType())) {
                continue;
            }
            try {
                DownloadedFile downloaded = files.download(ref.id(), currentUser);
                images.add(readImage(downloaded.file(), downloaded.resource()));
            } catch (RuntimeException missing) {
                // 已删除、已过期或不属于这个账号 —— 少一张图好过整段历史打不开
                log.debug("Skipping attachment {} in history: {}", ref.id(), missing.getMessage());
            }
        }
        return images;
    }

    private static void requireImageSize(UploadedFileEntity row) {
        if (row.getSizeBytes() > MAX_IMAGE_BYTES) {
            throw new InvalidRequestException(
                    "图片 '%s' 有 %d MB，超过 %d MB 上限，请压缩后再发"
                            .formatted(row.getOriginalName(),
                                    row.getSizeBytes() / (1024 * 1024),
                                    MAX_IMAGE_BYTES / (1024 * 1024)));
        }
    }

    private static LlmImageAttachment readImage(UploadedFileEntity row, Resource resource) {
        try (InputStream content = resource.getInputStream()) {
            return new LlmImageAttachment(row.getMimeType(), content.readAllBytes());
        } catch (IOException failure) {
            throw new InvalidRequestException(
                    "无法读取图片 '%s'".formatted(row.getOriginalName()));
        }
    }

    /**
     * 文本类与办公文档展开进 prompt。
     */
    private String readTextDocument(UploadedFileEntity row, Resource resource, int budget) {
        String ext = row.getExtension() == null ? "" : row.getExtension().toLowerCase(Locale.ROOT);
        boolean plainText = TEXT_EXTENSIONS.contains(ext) || isPlainTextMime(row.getMimeType());
        boolean office = documentTextExtractor.supports(ext)
                || Set.of("doc", "xls", "ppt").contains(ext);
        if (!plainText && !office) {
            throw new InvalidRequestException(
                    "附件 '%s'（%s）暂不支持直接送入对话。请上传图片，或 txt / md / csv / json / pdf / docx / xlsx / pptx"
                            .formatted(row.getOriginalName(),
                                    row.getMimeType() == null ? ext : row.getMimeType()));
        }
        if (budget <= 0) {
            return "文件「" + row.getOriginalName() + "」内容：（已达本轮文本上限，已省略）";
        }
        try (InputStream in = resource.getInputStream()) {
            String text = plainText
                    ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : documentTextExtractor.extract(ext, row.getOriginalName(), in);
            if (text == null) {
                text = "";
            }
            text = text.strip();
            if (text.isEmpty()) {
                return "文件「" + row.getOriginalName() + "」内容：（未提取到可复制文本，可能是扫描件或纯图）";
            }
            int cap = Math.min(MAX_FILE_TEXT_CHARS, budget);
            if (text.length() > cap) {
                text = text.substring(0, cap) + "\n…(截断)";
            }
            return "文件「" + row.getOriginalName() + "」内容：\n" + text;
        } catch (InvalidRequestException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new InvalidRequestException(
                    "无法读取附件 '%s'".formatted(row.getOriginalName()));
        }
    }

    private static boolean isImageMime(String mimeType) {
        return mimeType != null
                && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static boolean isPlainTextMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String normalized = mimeType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/")
                || "application/json".equals(normalized)
                || "application/csv".equals(normalized);
    }

    private static boolean isAudioMime(String mimeType) {
        if (mimeType == null) return false;
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("audio/") || "application/ogg".equals(normalized);
    }

    private String transcribe(UploadedFileEntity row, Resource resource,
                              ProviderCredentials credentials) {
        try (InputStream content = resource.getInputStream()) {
            return audioTranscription.transcribe(
                    content.readAllBytes(), row.getOriginalName(), row.getMimeType(), credentials);
        } catch (IOException failure) {
            throw new InvalidRequestException(
                    "无法读取音频 '%s'".formatted(row.getOriginalName()));
        }
    }

    /**
     * @param images         供模型使用的二进制图片，按用户选择的顺序
     * @param refs           落库用的引用，与附件一一对应
     * @param documentText   文本附件展开后的正文，可拼进 user content；无则为空串
     */
    public record Loaded(List<LlmImageAttachment> images,
                         List<ChatAttachmentRef> refs,
                         String documentText,
                         String audioTranscript) {

        public Loaded {
            images = images == null ? List.of() : List.copyOf(images);
            refs = refs == null ? List.of() : List.copyOf(refs);
            documentText = documentText == null ? "" : documentText;
            audioTranscript = audioTranscript == null ? "" : audioTranscript;
        }

        public static Loaded empty() {
            return new Loaded(List.of(), List.of(), "", "");
        }

        public boolean hasDocumentText() {
            return documentText != null && !documentText.isBlank();
        }

        public boolean hasAudioTranscript() {
            return audioTranscript != null && !audioTranscript.isBlank();
        }
    }
}
