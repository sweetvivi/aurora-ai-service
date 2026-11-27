package com.hub.backend.modules.file.event;

/**
 * A work was really deleted (row marked deleted, blob removed).
 *
 * <p>Published after the attachment row is gone so that other modules can drop what pointed at it
 * — the canvas material library keeps referencing rows, and a reference to a deleted picture is a
 * card that cannot be opened. Kept as an event so the file module does not depend on the modules
 * that happen to reference its rows.</p>
 */
public record FileDeletedEvent(Long fileId, Long userId) {
}
