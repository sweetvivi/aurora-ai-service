package com.hub.backend.modules.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体消息上那些「JSON 数组列」的编解码（工具调用、图片附件）。
 *
 * <p>两端都做成永不抛异常：写库失败只是丢掉一条界面附加信息，不值得让整轮对话失败；
 * 读库失败（历史数据被手工改坏、字段格式演进）也只是这批消息少了卡片或缩略图，
 * 不能连正文一起打不开。</p>
 */
@Component
public class AgentJsonColumnCodec {

    private static final Logger log = LoggerFactory.getLogger(AgentJsonColumnCodec.class);

    private final ObjectMapper objectMapper;

    public AgentJsonColumnCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 空集合返回 {@code null}：列留空比存一个 {@code []} 更能表达「没有」 */
    public String encode(List<?> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(items);
            return json == null || json.isBlank() ? null : json;
        } catch (JsonProcessingException failure) {
            log.warn("Failed to serialise {} entries: {}", items.size(), failure.getMessage());
            return null;
        }
    }

    public <T> List<T> decode(String json, Class<T> itemType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> parsed = objectMapper.readValue(
                    json, objectMapper.getTypeFactory().constructCollectionType(List.class, itemType));
            return parsed == null ? List.of() : parsed;
        } catch (JsonProcessingException failure) {
            log.warn("Failed to parse stored column ({} chars): {}", json.length(), failure.getMessage());
            return List.of();
        }
    }
}
