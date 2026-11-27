package com.hub.backend.modules.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 生图意图兜底：判定要保守，宁可漏判成问答，也不能把问答误判成生图。
 *
 * <p>这层兜底由 {@code app.chat.image-intent-fallback} 控制、默认关闭。正常链路里生图由模型
 * 自己调 {@code generate_image} 完成，这里只在中转不转发 tools 时才需要。</p>
 */
class ChatImageIntentTest {

    @Test
    void clearDrawRequestsAreDetected() {
        assertTrue(ChatService.looksLikeDrawRequest("帮我做一张都市美女图片，要求时尚一点的穿搭"));
        assertTrue(ChatService.looksLikeDrawRequest("画一只橘猫"));
        assertTrue(ChatService.looksLikeDrawRequest("帮我画一张海报"));
        assertTrue(ChatService.looksLikeDrawRequest("生成一张赛博朋克风格的壁纸"));
        assertTrue(ChatService.looksLikeDrawRequest("来张头像"));
    }

    @Test
    void questionsAboutImagesAreNotDrawRequests() {
        assertFalse(ChatService.looksLikeDrawRequest("你能生成图片吗"));
        assertFalse(ChatService.looksLikeDrawRequest("如何生成图片"));
        assertFalse(ChatService.looksLikeDrawRequest("生成图片支持哪些参数"));
        assertFalse(ChatService.looksLikeDrawRequest("帮我写一段生成都市美女图片的描述"));
        assertFalse(ChatService.looksLikeDrawRequest("生图的提示词应该怎么写"));
    }

    @Test
    void diagramStyleRequestsAreTextAnswers() {
        assertFalse(ChatService.looksLikeDrawRequest("画一张流程图"));
        assertFalse(ChatService.looksLikeDrawRequest("生成一个时序图"));
        assertFalse(ChatService.looksLikeDrawRequest("做一份数据表格"));
        assertFalse(ChatService.looksLikeDrawRequest("生成一份产品说明文档"));
    }

    @Test
    void plainTextIsIgnored() {
        assertFalse(ChatService.looksLikeDrawRequest(""));
        assertFalse(ChatService.looksLikeDrawRequest(null));
        assertFalse(ChatService.looksLikeDrawRequest("帮我把这段文字翻译成英文"));
    }

    @Test
    void jsonStringEscapesQuotesAndNewlines() {
        assertEquals("\"a\\\"b\"", ChatService.jsonString("a\"b"));
        assertEquals("\"a\\nb\"", ChatService.jsonString("a\nb"));
        assertEquals("\"c:\\\\tmp\"", ChatService.jsonString("c:\\tmp"));
    }

    /**
     * 生图提示词的口径：默认原话直出，改写只允许发生在用户只说一两个词的时候。
     *
     * <p>早先写的是「不要把用户的口语原句直接塞进 prompt」，模型于是无条件二次创作 ——
     * 用户说「白衬衫的都市女性站在地铁口」，出来变成「杂志大片、时尚穿搭」，这就是过度改写。
     * 这里把三条底线钉住，避免以后又被改回「必须扩写」。</p>
     */
    @Test
    void imagePromptDefaultsToUserWording() {
        String hint = ChatService.IMAGE_TOOLS_HINT;
        // 默认行为：原话直出
        assertTrue(hint.contains("默认把用户的原话直接当 prompt"));
        assertTrue(hint.contains("不要润色、不要改写"));
        // 扩写只在用户描述极简时发生，且只能加技术参数
        assertTrue(hint.contains("只说了一两个词"));
        assertTrue(hint.contains("能补的只有光线、视角、景深、画质、色调这五项"));
        assertTrue(hint.contains("其余一律不加"));
        // 否定要求不能被「优化」成正面描述
        assertTrue(hint.contains("必须仍然是否定"));
        // prompt 不带张数，否则上游会把多张画进一张画布
        assertTrue(hint.contains("只描述「一张图里有什么」"));
    }
}
