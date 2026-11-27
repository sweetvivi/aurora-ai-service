package com.hub.backend.modules.agent.tool;

import com.hub.backend.infra.llm.dto.LlmToolCall;
import com.hub.backend.infra.llm.dto.LlmToolDefinition;

import java.util.Map;

/**
 * One capability an agent can actually execute.
 *
 * <p>Distinct from {@code agent_abilities}: that table only stores labels the console shows. A
 * tool is the code behind a label — it declares a JSON schema for the model, runs against the
 * current user, and returns text the model can reason about plus optional structured metadata the
 * UI renders (image urls, for instance).</p>
 *
 * <p>Implementations must never throw: a failing tool has to come back as
 * {@link ToolOutcome#failed} so the model can explain the problem instead of the whole turn
 * collapsing.</p>
 */
public interface AgentTool {

    /** Name, description and JSON schema handed to the model. */
    LlmToolDefinition definition();

    /**
     * Ability key this tool answers to, matched against {@code agent_abilities.ability_key}.
     *
     * <p>Defaults to the tool name, so a tool named {@code generate_image} is enabled by giving
     * an agent the ability {@code generate_image}. Implementations only override it when the
     * console uses a different label.</p>
     */
    /** 能力键，默认用工具名；接口层的 abilityKey 与注册表按它匹配 */
    default String abilityKey() {
        return definition().name();
    }

    /**
     * 没有配置能力白名单时，这个工具是否默认可见。
     *
     * <p>默认 {@code true}，是为了兼容历史：能力表是后加的，早期智能体一条配置都没有，
     * 当年只有 {@code generate_image} 一个工具，全开是合理的。</p>
     *
     * <p>但<b>业务工具必须返回 false</b>。工具定义每轮都随请求重发，一个业务工具往往自带
     * 几百上千字的清单；如果默认全开，136 个角色智能体（写代码的、聊电影的）每次对话都会
     * 白背一份「换背景 / 换模特 / 换材质」的说明 —— 既费钱，也让角色不纯。这类能力属于
     * 「多个工具或智能体联动」的场景，应该由运营在管理端显式开给指定的智能体。</p>
     */
    default boolean enabledByDefault() {
        return true;
    }

    /** Runs the tool once. */
    ToolOutcome execute(LlmToolCall call, ToolContext context);

    /**
     * @param success whether the tool produced a usable result
     * @param content text fed back to the model as the tool message
     * @param meta    structured extras for the client only, never sent to the model
     */
    record ToolOutcome(boolean success, String content, Map<String, Object> meta) {

        public ToolOutcome {
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }

        public static ToolOutcome ok(String content) {
            return new ToolOutcome(true, content, Map.of());
        }

        public static ToolOutcome ok(String content, Map<String, Object> meta) {
            return new ToolOutcome(true, content, meta);
        }

        public static ToolOutcome failed(String message) {
            return new ToolOutcome(false, message, Map.of());
        }

        /**
         * Failure with a model-facing {@code content} and client-only {@code meta}
         * (e.g. {@code userMessage} / {@code errorCode} for UI banners).
         */
        public static ToolOutcome failed(String modelContent, Map<String, Object> meta) {
            return new ToolOutcome(false, modelContent, meta);
        }
    }
}
