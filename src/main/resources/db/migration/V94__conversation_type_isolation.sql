-- V94: 会话类型隔离（绘画作品 vs 模型对话）。
--
-- 绘画页把每张生成图存成一个会话：标题 `AI绘画：{prompt}`，提示词以一条 user 消息挂在会话下，
-- 图片以附件形式指向同一个会话。它和模型对话共用 conversations 表，而 conversation_type
-- 一直恒为 'CHAT'，于是这些「作品容器」会出现在聊天的对话记录里 —— 点开只有一句提示词和一张图。
--
-- 这里做两件事：
--   1. 把存量的绘画会话按标题前缀回填成 'PAINTING'（前缀是绘画页建会话时写的，改名过的会话
--      无法识别，保持 'CHAT'：它们仍会出现在聊天历史里，属于已知的少量残留）；
--   2. 为「按账号 + 类型 + 状态 + 更新时间」的历史查询补一个复合索引，
--      避免过滤后回表扫描。
--
-- 幂等：已经是 'PAINTING' 的行不会被重复处理；索引已存在时跳过。

UPDATE conversations
SET conversation_type = 'PAINTING'
WHERE conversation_type = 'CHAT'
  AND title LIKE 'AI绘画：%';

SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'conversations'
      AND index_name = 'idx_conversations_user_type_updated'
);

SET @ddl := IF(
    @idx_exists = 0,
    'CREATE INDEX idx_conversations_user_type_updated ON conversations (user_id, conversation_type, status, updated_at)',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
