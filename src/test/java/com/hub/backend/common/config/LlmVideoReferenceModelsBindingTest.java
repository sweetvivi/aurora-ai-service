package com.hub.backend.common.config;

import com.hub.backend.common.config.properties.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code app.llm.video-input-reference-models} 的绑定行为。
 *
 * <p>这一项决定参考图模式要不要带 {@code input_reference}，而 xAI 通道会把这个字段提升成首帧，
 * 所以「没配置」必须真的是空集合、不能是 {@code [""]} 之类的东西；一旦判成「名单命中」，
 * 参考图模式就会悄悄退化成首帧。逗号分隔的多值绑定也一并钉住，方便按需给 sora 类通道开口子。</p>
 */
class LlmVideoReferenceModelsBindingTest {

    @Test
    void blankConfigurationBindsToEmptyList() {
        LlmProperties properties = bind("");

        assertTrue(properties.getVideoInputReferenceModels().isEmpty(),
                "默认（空值）必须是空集合，不能带出空字符串元素");
    }

    @Test
    void absentConfigurationBindsToEmptyList() {
        LlmProperties properties = bind(null);

        assertTrue(properties.getVideoInputReferenceModels().isEmpty());
    }

    @Test
    void commaSeparatedValueBindsToEveryModel() {
        LlmProperties properties = bind("seedance-2.0, sora-2");

        assertEquals(List.of("seedance-2.0", "sora-2"), properties.getVideoInputReferenceModels());
    }

    private static LlmProperties bind(String raw) {
        MapPropertySource source = new MapPropertySource("test", raw == null
                ? Map.of()
                : Map.of("app.llm.video-input-reference-models", raw));
        return new Binder(ConfigurationPropertySources.from(source))
                .bind("app.llm", Bindable.of(LlmProperties.class))
                .orElseGet(LlmProperties::new);
    }
}
