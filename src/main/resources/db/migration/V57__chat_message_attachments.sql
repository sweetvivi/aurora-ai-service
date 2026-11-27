-- V57: 普通聊天消息记录附件引用（图片 / 文档）
--
-- 背景：attachmentIds 只在当次 /chat/stream 送给模型，落库的 user 行只有纯文本，
-- 历史重开后前端拼不出缩略图和文档卡片。
--
-- 做法对齐智能体（V33）：在消息上挂 JSON 数组，只存引用不存二进制：
--   [{"id":5,"mimeType":"image/png","originalName":"shirt.png","sizeBytes":20480,
--     "url":"/api/v1/files/5/content"}]
--
-- 空表示这条消息没有附件（历史数据全是这种）。

ALTER TABLE chat_messages ADD COLUMN attachments_json LONGTEXT NULL AFTER content;
