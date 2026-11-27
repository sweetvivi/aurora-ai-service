package com.hub.backend.modules.thesis.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One heading of the outline tree.
 *
 * <p>The client edits these directly in the tree view, so the payload carries the tree shape
 * (nested {@code children}) instead of a flattened list with parent pointers. On save the server
 * rebuilds the tree from this structure, re-assigns ids to anything missing and normalises depth —
 * the client never needs to compute numbering.</p>
 *
 * @param id       server assigned node id; null means "new node, assign one"
 * @param title    heading text, without the numeric prefix
 * @param level    1 = 章, 2 = 节, 3 = 目
 * @param children child headings, may be empty
 */
@Schema(name = "ThesisOutlineNode", description = "大纲节点（三级结构）")
public record ThesisOutlineNode(
        @Schema(description = "节点 ID，新增节点传 null 由服务端分配", example = "n_lx2k9a_1") String id,
        @Schema(description = "标题，不含编号前缀", example = "研究背景与意义") String title,
        @Schema(description = "层级：1 章 / 2 节 / 3 目", example = "2") Integer level,
        @Schema(description = "子节点") java.util.List<ThesisOutlineNode> children
) {
}
