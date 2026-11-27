package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outcome of one tool click.
 *
 * <p>Two ways a result can carry a file:</p>
 * <ul>
 *   <li>{@code fileFormat} without {@code fileId} — the bytes are rebuilt on demand by
 *       {@code GET /documents/{id}/export?format=…} (text → Word, text → txt).</li>
 *   <li>{@code fileId} — the action produced a new blob (merge, split, encrypt, watermark,
 *       images, text → PDF) that already lives in the attachment store, so the page downloads it
 *       through {@code GET /files/{fileId}/content}, the same authenticated route as any upload.</li>
 * </ul>
 *
 * @param action      echo of the requested action
 * @param label       tool label to show in the result header
 * @param text        human readable result body, plain text (no HTML)
 * @param fileName    suggested file name when a file is produced, otherwise {@code null}
 * @param fileFormat  {@code docx} / {@code txt} / {@code pdf} / {@code zip} when a file is
 *                    produced, otherwise {@code null}
 * @param truncated   true when the model only saw an excerpt of a very long document
 * @param fileId      id of a stored blob when the action produced one, otherwise {@code null}
 */
@Schema(name = "DocumentActionResult", description = "文档处理动作的结果")
public record DocumentActionResult(
        String action,
        String label,
        String text,
        String fileName,
        String fileFormat,
        boolean truncated,
        Long fileId
) {

    public static DocumentActionResult text(String action, String label, String text) {
        return new DocumentActionResult(action, label, text, null, null, false, null);
    }

    public static DocumentActionResult text(String action, String label, String text, boolean truncated) {
        return new DocumentActionResult(action, label, text, null, null, truncated, null);
    }

    public static DocumentActionResult file(String action, String label, String text,
                                            String fileName, String fileFormat, boolean truncated) {
        return new DocumentActionResult(action, label, text, fileName, fileFormat, truncated, null);
    }

    /** A file that is already stored, so the page fetches it by id instead of re-deriving it. */
    public static DocumentActionResult stored(String action, String label, String text,
                                              String fileName, String fileFormat, Long fileId) {
        return new DocumentActionResult(action, label, text, fileName, fileFormat, false, fileId);
    }
}
