-- V32: 智能体消息记录工具调用
--
-- 背景：工具调用只存在于实时 SSE 流里（tool.call / tool.result 两帧），
-- 消息表里没有任何痕迹。用户刷新页面后，历史消息里只剩正文，
-- 「刚才那张图是模型自己生出来的」这件事就看不出来了。
--
-- 做法：在 assistant 消息上挂一段 JSON 数组，保存本轮发生过的工具调用：
--   [{"id":"call_1","name":"generate_image","success":true,
--     "content":"...","meta":{"images":[{"url":"/api/v1/files/6/content","fileId":6}]}}]
--
-- 为什么不建独立的 tool_calls 表：工具调用与「某一条 assistant 消息」是
-- 一对一的生命周期（消息删了调用就该消失），查询也永远跟着消息一起取，
-- 单独建表只会多一次 join 和一次级联删除的维护成本。
--
-- 这一列是纯增量：为空表示这条消息没有调用过工具（历史数据全是这种），
-- 前端渲染时按空数组处理。

ALTER TABLE agent_chat_messages ADD COLUMN tool_calls_json LONGTEXT NULL AFTER thinking_content;
