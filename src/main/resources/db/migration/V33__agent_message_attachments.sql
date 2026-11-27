-- V33: 智能体消息记录图片附件
--
-- 背景：之前用户只能发文字（ChatMessagePayload 只有 role + content），在一个做图像的
-- 产品里问「这张图怎么调」都做不到。
--
-- 做法：在消息上挂一段 JSON 数组，保存随消息送进模型的图片附件：
--   [{"id":5,"mimeType":"image/png","originalName":"shirt.png","sizeBytes":20480,
--     "url":"/api/v1/files/5/content"}]
--
-- 只存引用不存二进制：本体已经在 uploaded_files + 磁盘里，这里再存一份既浪费空间，
-- 又会出现「两处内容不一致」的问题。重建上下文时按 id 回读，读不到就跳过这张图。
--
-- 纯增量：为空表示这条消息没有附件（历史数据全是这种）。

ALTER TABLE agent_chat_messages ADD COLUMN attachments_json LONGTEXT NULL AFTER tool_calls_json;
