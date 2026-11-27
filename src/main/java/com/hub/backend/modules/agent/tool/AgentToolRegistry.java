package com.hub.backend.modules.agent.tool;

import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Collects every {@link AgentTool} bean so the chat orchestration can declare them to the model.
 *
 * <p>Adding a capability means adding one {@code @Component} implementation — no registry edits,
 * no switch statement.</p>
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> byName;

    public AgentToolRegistry(List<AgentTool> discovered) {
        Map<String, AgentTool> index = new LinkedHashMap<>();
        for (AgentTool tool : discovered) {
            String name = tool.definition().name();
            if (index.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Duplicate agent tool name: " + name);
            }
        }
        this.byName = Map.copyOf(index);
    }

    public List<LlmToolDefinition> definitions() {
        return byName.values().stream().map(AgentTool::definition).toList();
    }

    /**
     * Tools an agent may use, given the abilities configured on it.
     *
     * <p>An agent without abilities gets only the tools that are {@link AgentTool#enabledByDefault()
     * enabled by default}. Historically this meant "everything", because the ability table was added
     * after the agents and the only tool was image generation — keeping that literal behaviour now
     * would leak business tools (a 56-entry e-commerce catalogue, thousands of characters) into every
     * role-playing agent's request. So the default is a per-tool opt-in instead.</p>
     *
     * <p>As soon as one ability is configured the list becomes a whitelist: listed keys are matched
     * against {@link AgentTool#abilityKey()}, unknown keys are ignored rather than failing the
     * request. This also means a business tool stays available to an agent that lists it explicitly,
     * regardless of {@code enabledByDefault}.</p>
     */
    public List<AgentTool> resolve(Collection<String> abilityKeys) {
        if (abilityKeys == null || abilityKeys.isEmpty()) {
            return byName.values().stream().filter(AgentTool::enabledByDefault).toList();
        }
        Set<String> wanted = new HashSet<>(abilityKeys);
        return byName.values().stream().filter(tool -> wanted.contains(tool.abilityKey())).toList();
    }

    /** Same as {@link #resolve}, shaped for the request payload. */
    public List<LlmToolDefinition> definitionsFor(Collection<String> abilityKeys) {
        return resolve(abilityKeys).stream().map(AgentTool::definition).toList();
    }

    public Optional<AgentTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }
}
