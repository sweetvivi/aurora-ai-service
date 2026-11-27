package com.hub.backend.modules.support;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.common.exception.NotFoundException;
import com.hub.backend.modules.support.dto.SupportDocResponse;
import com.hub.backend.modules.support.dto.SupportFaqItemResponse;
import com.hub.backend.modules.support.dto.SupportFaqUpsertRequest;
import com.hub.backend.modules.support.entity.SupportDocChunkEntity;
import com.hub.backend.modules.support.entity.SupportDocEntity;
import com.hub.backend.modules.support.entity.SupportFaqEntity;
import com.hub.backend.modules.support.repository.SupportDocChunkRepository;
import com.hub.backend.modules.support.repository.SupportDocRepository;
import com.hub.backend.modules.support.repository.SupportFaqRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class SupportKnowledgeAdminService {

    private final SupportFaqRepository faqs;
    private final SupportDocRepository docs;
    private final SupportDocChunkRepository chunks;

    public SupportKnowledgeAdminService(SupportFaqRepository faqs,
                                        SupportDocRepository docs,
                                        SupportDocChunkRepository chunks) {
        this.faqs = faqs;
        this.docs = docs;
        this.chunks = chunks;
    }

    public List<SupportFaqItemResponse> listFaqs(Long siteId) {
        return faqs.findBySiteIdOrderBySortOrderAscIdAsc(siteId).stream()
                .map(this::toFaq)
                .toList();
    }

    @Transactional
    public SupportFaqItemResponse createFaq(Long siteId, SupportFaqUpsertRequest request) {
        SupportFaqEntity entity = new SupportFaqEntity();
        entity.setSiteId(siteId);
        applyFaq(entity, request);
        return toFaq(faqs.save(entity));
    }

    @Transactional
    public SupportFaqItemResponse updateFaq(Long id, SupportFaqUpsertRequest request) {
        SupportFaqEntity entity = faqs.findById(id)
                .orElseThrow(() -> new NotFoundException("FAQ 不存在"));
        applyFaq(entity, request);
        return toFaq(faqs.save(entity));
    }

    @Transactional
    public void deleteFaq(Long id) {
        if (!faqs.existsById(id)) {
            throw new NotFoundException("FAQ 不存在");
        }
        faqs.deleteById(id);
    }

    public List<SupportDocResponse> listDocs(Long siteId) {
        return docs.findBySiteIdOrderByCreatedAtDescIdDesc(siteId).stream()
                .map(doc -> toDoc(doc, chunks.findByDocIdIn(List.of(doc.getId())).size()))
                .toList();
    }

    @Transactional
    public SupportDocResponse uploadDoc(Long siteId, MultipartFile file, String title, Boolean enabled) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")
                || lower.endsWith(".html") || lower.endsWith(".htm"))) {
            throw new InvalidRequestException("一期仅支持 txt / md / csv / html 文本文件");
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new InvalidRequestException("无法读取上传文件");
        }
        if (text.isBlank()) {
            throw new InvalidRequestException("文件内容为空");
        }
        if (text.length() > 500_000) {
            throw new InvalidRequestException("文件过大，请控制在约 50 万字符以内");
        }

        SupportDocEntity doc = new SupportDocEntity();
        doc.setSiteId(siteId);
        doc.setTitle(title == null || title.isBlank() ? stripExtension(fileName) : title.strip());
        doc.setFileName(fileName);
        doc.setContentType(file.getContentType());
        doc.setRawText(text);
        doc.setEnabled(enabled == null || enabled);
        SupportDocEntity saved = docs.save(doc);

        List<String> parts = chunk(text);
        List<SupportDocChunkEntity> rows = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i += 1) {
            rows.add(new SupportDocChunkEntity(saved.getId(), i, parts.get(i)));
        }
        chunks.saveAll(rows);
        return toDoc(saved, rows.size());
    }

    @Transactional
    public SupportDocResponse setDocEnabled(Long id, boolean enabled) {
        SupportDocEntity doc = docs.findById(id)
                .orElseThrow(() -> new NotFoundException("文档不存在"));
        doc.setEnabled(enabled);
        SupportDocEntity saved = docs.save(doc);
        return toDoc(saved, chunks.findByDocIdIn(List.of(saved.getId())).size());
    }

    @Transactional
    public void deleteDoc(Long id) {
        if (!docs.existsById(id)) {
            throw new NotFoundException("文档不存在");
        }
        chunks.deleteByDocId(id);
        docs.deleteById(id);
    }

    private void applyFaq(SupportFaqEntity entity, SupportFaqUpsertRequest request) {
        entity.setQuestion(request.question().strip());
        entity.setAnswer(request.answer().strip());
        entity.setKeywords(request.keywords() == null || request.keywords().isBlank()
                ? null : request.keywords().strip());
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(request.sortOrder());
        }
    }

    private SupportFaqItemResponse toFaq(SupportFaqEntity faq) {
        return new SupportFaqItemResponse(
                faq.getId(),
                faq.getQuestion(),
                faq.getAnswer(),
                faq.getKeywords() == null ? "" : faq.getKeywords(),
                faq.isEnabled(),
                faq.getSortOrder());
    }

    private SupportDocResponse toDoc(SupportDocEntity doc, int chunkCount) {
        return new SupportDocResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getFileName(),
                doc.getContentType(),
                doc.isEnabled(),
                chunkCount,
                doc.getCreatedAt(),
                doc.getUpdatedAt());
    }

    static List<String> chunk(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        String[] paragraphs = normalized.split("\n{2,}");
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String part = paragraph.strip();
            if (part.isEmpty()) {
                continue;
            }
            if (current.isEmpty()) {
                current.append(part);
            } else if (current.length() + part.length() + 2 <= SupportConstants.CHUNK_SIZE) {
                current.append("\n\n").append(part);
            } else {
                result.add(current.toString());
                current.setLength(0);
                if (part.length() <= SupportConstants.CHUNK_SIZE) {
                    current.append(part);
                } else {
                    result.addAll(hardSplit(part));
                }
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        if (result.isEmpty() && !normalized.isEmpty()) {
            result.addAll(hardSplit(normalized));
        }
        return result;
    }

    private static List<String> hardSplit(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += SupportConstants.CHUNK_SIZE) {
            parts.add(text.substring(i, Math.min(text.length(), i + SupportConstants.CHUNK_SIZE)));
        }
        return parts;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
