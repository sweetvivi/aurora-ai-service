package com.hub.backend.modules.agent.tool;

import com.hub.backend.infra.llm.dto.LlmToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hub.backend.modules.image.ImageGenerationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * 能力 → 工具的白名单语义。
 *
 * <p>这段规则决定「模型能不能看到某个工具」，属于容易改错、但改错了很难从界面察觉的地方，
 * 所以单独锁住行为：未配置 = 只开默认工具，配置了 = 只给匹配项。</p>
 */
class AgentToolRegistryTest {

    @Test
    void agentWithoutAbilitiesSeesOnlyDefaultTools() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                stub("generate_image", true), stub("run_commerce_tool", false)));

        List<LlmToolDefinition> offered = registry.definitionsFor(List.of());
        assertEquals(1, offered.size());
        assertEquals("generate_image", offered.get(0).name());

        // null 与空集合同义：历史智能体一条能力都没配
        assertEquals(1, registry.definitionsFor(null).size());
    }

    @Test
    void businessToolsStayInvisibleUntilExplicitlyGranted() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                stub("generate_image", true), stub("run_commerce_tool", false)));

        // 关键回归：默认关闭的业务工具不能因为「角色智能体没配能力」就混进工具清单
        assertTrue(registry.definitionsFor(List.of()).stream()
                .noneMatch(tool -> tool.name().equals("run_commerce_tool")));

        List<LlmToolDefinition> granted = registry.definitionsFor(List.of("run_commerce_tool"));
        assertEquals(1, granted.size());
        assertEquals("run_commerce_tool", granted.get(0).name());
    }

    @Test
    void configuredAbilitiesActAsWhitelist() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                stub("generate_image", true), stub("web_search", true)));

        List<LlmToolDefinition> offered = registry.definitionsFor(List.of("web_search"));

        assertEquals(1, offered.size());
        assertEquals("web_search", offered.get(0).name());
    }

    @Test
    void unknownAbilitiesOfferNothing() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(stub("generate_image", true)));

        assertTrue(registry.definitionsFor(List.of("not_a_tool")).isEmpty());
    }

    @Test
    void duplicateToolNameIsRejectedAtStartup() {
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new AgentToolRegistry(List.of(
                        stub("generate_image", true), stub("generate_image", true))));

        assertTrue(failure.getMessage().contains("generate_image"));
    }

    @Test
    void imageGenerationIsNotExposedToAgentsWithoutAnExplicitAbility() {
        ImageGenerationTool tool = new ImageGenerationTool(mock(ImageGenerationService.class), new ObjectMapper());

        assertFalse(tool.enabledByDefault());
    }

    private static AgentTool stub(String name, boolean enabledByDefault) {
        return new AgentTool() {
            @Override
            public LlmToolDefinition definition() {
                return new LlmToolDefinition(name, "stub", Map.of("type", "object"));
            }

            @Override
            public boolean enabledByDefault() {
                return enabledByDefault;
            }

            @Override
            public ToolOutcome execute(com.hub.backend.infra.llm.dto.LlmToolCall call,
                                       ToolContext context) {
                return ToolOutcome.ok("{}");
            }
        };
    }
}
