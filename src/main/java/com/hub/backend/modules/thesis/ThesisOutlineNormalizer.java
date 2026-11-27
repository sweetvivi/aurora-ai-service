package com.hub.backend.modules.thesis;

import com.hub.backend.common.exception.InvalidRequestException;
import com.hub.backend.modules.thesis.dto.ThesisOutlineNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates and normalises an outline tree sent by the editor.
 *
 * <p>Three rules, all of which the client can satisfy without thinking about them:</p>
 * <ol>
 *   <li><b>Depth</b> — top level items are chapters (1), their children sections (2), grandchildren
 *       items (3). A level sent that contradicts the position is corrected, not rejected: the
 *       editor's "add sibling at the same level" button can produce a level 3 row under a chapter,
 *       and rejecting would lose the user's edit over an internal detail.</li>
 *   <li><b>Titles</b> — trimmed, collapsed whitespace, at most 120 characters. Blank titles are
 *       rejected because a heading with no text cannot be rendered or exported.</li>
 *   <li><b>Ids</b> — missing ids get a fresh one, duplicated ids keep the first occurrence and get a
 *       fresh one afterwards. Ids are opaque to the client and only used for editor keys.</li>
 * </ol>
 */
@Component
public class ThesisOutlineNormalizer {

    private static final int MAX_DEPTH = 3;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_NODES = 400;

    public List<ThesisOutlineNode> normalize(List<ThesisOutlineNode> incoming) {
        if (incoming == null) {
            throw new InvalidRequestException("outline must not be null");
        }
        List<ThesisOutlineNode> result = walk(incoming, 1, new int[]{0}, new HashMap<>());
        if (result.isEmpty()) {
            throw new InvalidRequestException("outline must contain at least one chapter");
        }
        return result;
    }

    private List<ThesisOutlineNode> walk(List<ThesisOutlineNode> nodes, int depth, int[] budget,
                                         Map<String, Boolean> seenIds) {
        List<ThesisOutlineNode> out = new ArrayList<>();
        if (nodes == null || depth > MAX_DEPTH) {
            return out;
        }
        for (ThesisOutlineNode node : nodes) {
            if (node == null) {
                continue;
            }
            if (++budget[0] > MAX_NODES) {
                throw new InvalidRequestException("outline must contain at most " + MAX_NODES + " nodes");
            }
            String title = cleanTitle(node.title());
            String id = node.id() == null || node.id().isBlank() ? newId() : node.id().strip();
            if (seenIds.putIfAbsent(id, Boolean.TRUE) != null) {
                id = newId();
                seenIds.put(id, Boolean.TRUE);
            }
            out.add(new ThesisOutlineNode(id, title, depth, walk(node.children(), depth + 1, budget, seenIds)));
        }
        return out;
    }

    private static String cleanTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("outline node title must not be blank");
        }
        String title = raw.replaceAll("\\s+", " ").strip();
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        return title;
    }

    private static String newId() {
        return "n_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
