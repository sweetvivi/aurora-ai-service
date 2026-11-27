package com.hub.backend.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 一次工具调用的落库形态，与 SSE 的 {@code tool.call} / {@code tool.result} 两帧合并后一一对应。
 *
 * <p>存下来是为了刷新页面还能看到「模型自己调了工具」这一事实 —— 实时流结束之后，
 * 除了正文，没有任何地方能证明这张图是模型生成的。</p>
 *
 * @param id      模型分配的调用 ID（并行调用时用于区分）
 * @param name    工具名，如 {@code generate_image}
 * @param success 是否成功；失败时 {@code content} 就是给用户看的错误原因
 * @param content 工具回给模型的文本，图片工具里是一段 JSON
 * @param meta    只给界面用的结构化附加信息，比如图片地址
 */
@Schema(name = "AgentToolCallRecord", description = "智能体一次工具调用的记录")
public record AgentToolCallRecord(
        String id,
        String name,
        boolean success,
        String content,
        Map<String, Object> meta
) {

    public AgentToolCallRecord {
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
}
