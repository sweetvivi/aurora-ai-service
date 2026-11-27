-- V95: 视频工作台的存量会话也归位，别再落到模型对话历史里。
--
-- 视频生成页早期版本和绘画页一样，把每次生成存成一个会话：标题 `AI视频：{prompt}`，
-- 提示词以一条 user 消息挂在会话下。V94 只回填了 `AI绘画：`，这批 `AI视频：` 仍是 'CHAT'，
-- 于是那条没被删掉的会话会出现在聊天的对话记录里。
--
-- 这里把它们标成 'VIDEO'：
--   - 聊天只查 'CHAT'，不会再看到；
--   - 绘画只查 'PAINTING'，不受影响；
--   - 行本身保留（状态、消息、附件都不动），管理端/分享仍可按 id 访问。
--
-- 'VIDEO' 不在客户端可创建的白名单里 —— 只是给存量数据打的标签，不开放新会话创建。
--
-- 幂等：已经是 'VIDEO' 的行不会被重复处理。

UPDATE conversations
SET conversation_type = 'VIDEO'
WHERE conversation_type = 'CHAT'
  AND title LIKE 'AI视频：%';
