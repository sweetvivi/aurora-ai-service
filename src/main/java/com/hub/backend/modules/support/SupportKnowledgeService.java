package com.hub.backend.modules.support;

import com.hub.backend.modules.support.entity.SupportDocChunkEntity;
import com.hub.backend.modules.support.entity.SupportDocEntity;
import com.hub.backend.modules.support.entity.SupportFaqEntity;
import com.hub.backend.modules.support.repository.SupportDocChunkRepository;
import com.hub.backend.modules.support.repository.SupportDocRepository;
import com.hub.backend.modules.support.repository.SupportFaqRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * FAQ-first keyword retrieval plus document chunk scoring. No vector store in v1.
 */
@Service
@Transactional(readOnly = true)
public class SupportKnowledgeService {

    private final SupportFaqRepository faqs;
    private final SupportDocRepository docs;
    private final SupportDocChunkRepository chunks;

    public SupportKnowledgeService(SupportFaqRepository faqs,
                                   SupportDocRepository docs,
                                   SupportDocChunkRepository chunks) {
        this.faqs = faqs;
        this.docs = docs;
        this.chunks = chunks;
    }

    public String buildAppendix(Long siteId, String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return null;
        }
        List<ScoredSnippet> snippets = new ArrayList<>();
        for (SupportFaqEntity faq : faqs.findBySiteIdAndEnabledTrueOrderBySortOrderAscIdAsc(siteId)) {
            int score = scoreFaq(faq, q);
            if (score > 0) {
                snippets.add(new ScoredSnippet(score + 1000,
                        "FAQ: " + faq.getQuestion() + "\nA: " + faq.getAnswer()));
            }
        }
        List<SupportDocEntity> enabledDocs = docs.findBySiteIdAndEnabledTrueOrderByCreatedAtDescIdDesc(siteId);
        if (!enabledDocs.isEmpty()) {
            List<Long> docIds = enabledDocs.stream().map(SupportDocEntity::getId).toList();
            for (SupportDocChunkEntity chunk : chunks.findByDocIdIn(docIds)) {
                int score = scoreText(chunk.getContent(), q);
                if (score > 0) {
                    snippets.add(new ScoredSnippet(score, "DOC: " + chunk.getContent()));
                }
            }
        }
        if (snippets.isEmpty()) {
            // Still give the model a few FAQs so routine answers stay grounded.
            for (SupportFaqEntity faq : faqs.findBySiteIdAndEnabledTrueOrderBySortOrderAscIdAsc(siteId)
                    .stream().limit(3).toList()) {
                snippets.add(new ScoredSnippet(1,
                        "FAQ: " + faq.getQuestion() + "\nA: " + faq.getAnswer()));
            }
        }
        snippets.sort(Comparator.comparingInt(ScoredSnippet::score).reversed());
        StringBuilder sb = new StringBuilder("【知识库摘录】下列内容供你回答参考，勿编造未出现的事实：\n");
        int used = 0;
        int count = 0;
        for (ScoredSnippet snippet : snippets) {
            if (count >= SupportConstants.KNOWLEDGE_TOP_K) {
                break;
            }
            if (used + snippet.text().length() > SupportConstants.KNOWLEDGE_MAX_CHARS) {
                break;
            }
            sb.append("\n---\n").append(snippet.text()).append('\n');
            used += snippet.text().length();
            count += 1;
        }
        return count == 0 ? null : sb.toString();
    }

    private static int scoreFaq(SupportFaqEntity faq, String query) {
        String q = normalize(query);
        String question = normalize(faq.getQuestion());
        String keywords = normalize(faq.getKeywords());
        String answer = normalize(faq.getAnswer());
        int score = 0;
        if (question.contains(q) || q.contains(question)) {
            score += 50;
        }
        for (String token : tokenize(q)) {
            if (token.length() < 2) {
                continue;
            }
            if (question.contains(token)) {
                score += 8;
            }
            if (keywords.contains(token)) {
                score += 10;
            }
            if (answer.contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private static int scoreText(String text, String query) {
        String body = normalize(text);
        int score = 0;
        for (String token : tokenize(normalize(query))) {
            if (token.length() < 2) {
                continue;
            }
            if (body.contains(token)) {
                score += 3;
            }
        }
        return score;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static List<String> tokenize(String value) {
        String[] parts = value.split("[\\s,;|，；、/\\\\]+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        // Also slide short CJK bigrams for rough Chinese matching without a segmenter.
        String compact = value.replaceAll("\\s+", "");
        if (compact.length() >= 2 && compact.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp)
                == Character.UnicodeScript.HAN)) {
            for (int i = 0; i < compact.length() - 1; i += 1) {
                tokens.add(compact.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private record ScoredSnippet(int score, String text) {
    }
}
