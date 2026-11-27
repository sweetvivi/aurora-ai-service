package com.hub.backend.modules.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What the workspace can actually do right now.
 *
 * <p>The page used to hard-code which tools were demos and which were "待上线". Returning that
 * list from the server keeps the two in sync: a tool is clickable exactly when the backend
 * knows how to run it, and adding an action here is the only change needed to light it up.</p>
 *
 * <p>{@code rendererAvailable} covers the one case the action list alone cannot express: the
 * renderer-backed conversions are implemented but depend on a LibreOffice binary that may not be
 * installed on this host. Reporting it lets the page grey those four tools out with an honest
 * reason instead of failing on click.</p>
 *
 * @param actions         supported tool keys
 * @param formats         format labels the page may show
 * @param maxUploadBytes  per file limit enforced by the backend
 * @param rendererAvailable true when a document renderer is deployed on this host
 * @param renderingActions tool keys that need the renderer
 */
@Schema(name = "DocumentCapabilities", description = "文档处理能力清单")
public record DocumentCapabilities(
        List<String> actions,
        List<String> formats,
        long maxUploadBytes,
        boolean rendererAvailable,
        List<String> renderingActions
) {
}
