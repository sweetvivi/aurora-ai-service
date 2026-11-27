package com.hub.backend.modules.conversation;

import com.hub.backend.modules.conversation.entity.ConversationEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话标题的收敛规则。
 *
 * <p>标题通常直接取用户第一条消息，一条长 prompt 上千字很常见。早前这个值原样进库，
 * 触发 {@code title must be at most 255 characters}，整轮对话在入口被打回 400，
 * 用户只看到「请求参数有误」。截断必须发生在实体这一层，任何调用方都绕不过去。</p>
 */
class ConversationTitleTest {

    @Test
    void keepsShortTitleAsIs() {
        assertEquals("帮我做4张美女的图片", ConversationEntity.normalizeTitle("  帮我做4张美女的图片  "));
    }

    @Test
    void blankTitleBecomesNull() {
        assertNull(ConversationEntity.normalizeTitle(null));
        assertNull(ConversationEntity.normalizeTitle("   "));
    }

    @Test
    void truncatesLongPromptToColumnWidth() {
        String prompt = "生成一张高精度3D半写实国漫古风女性四视图设定图，".repeat(80);
        String title = ConversationEntity.normalizeTitle(prompt);
        assertEquals(ConversationEntity.TITLE_MAX_LENGTH, title.length());
        assertTrue(prompt.startsWith(title));
    }

    @Test
    void doesNotSplitSurrogatePair() {
        // 每个 emoji 占两个 char；截断点正好落在代理对上时不能留下半个字符
        String emoji = "😀";
        StringBuilder raw = new StringBuilder();
        while (raw.length() < ConversationEntity.TITLE_MAX_LENGTH) {
            raw.append(emoji);
        }
        String title = ConversationEntity.normalizeTitle(raw.toString());
        assertTrue(title.length() <= ConversationEntity.TITLE_MAX_LENGTH);
        // 末位不能是孤立的高位代理
        assertFalse(Character.isHighSurrogate(title.charAt(title.length() - 1)));
    }

    @Test
    void constructorAndRenameBothTruncate() {
        String prompt = "画".repeat(4000);
        ConversationEntity created = new ConversationEntity(1L, 1L, prompt);
        assertEquals(ConversationEntity.TITLE_MAX_LENGTH, created.getTitle().length());

        created.rename("改".repeat(1000));
        assertEquals(ConversationEntity.TITLE_MAX_LENGTH, created.getTitle().length());
    }

    @Test
    void derivedTitleAlsoTruncates() {
        ConversationEntity created = new ConversationEntity(1L, 1L, null);
        assertTrue(created.applyTitleIfBlank("标".repeat(1000)));
        assertEquals(ConversationEntity.TITLE_MAX_LENGTH, created.getTitle().length());
        // 已有标题时不再覆盖
        assertFalse(created.applyTitleIfBlank("新标题"));
    }
}
